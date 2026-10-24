package com.arnoutpro.ipod3g;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds Sonos speakers on the Wi-Fi and talks to them with their own UPnP control protocol, the
 * same one the Sonos app uses. Everything here blocks: call it on a background thread.
 */
final class Sonos {
    private static final String SEARCH = "M-SEARCH * HTTP/1.1\r\n"
            + "HOST: 239.255.255.250:1900\r\n"
            + "MAN: \"ssdp:discover\"\r\n"
            + "MX: 1\r\n"
            + "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n";

    /** A Sonos group (or a single speaker), controlled through its coordinator. */
    static final class Group {
        final String name, host, uuid;

        Group(String name, String host, String uuid) {
            this.name = name;
            this.host = host;
            this.uuid = uuid;
        }

        @Override public boolean equals(Object o) { return o instanceof Group && ((Group) o).uuid.equals(uuid); }

        @Override public int hashCode() { return uuid.hashCode(); }
    }

    private Sonos() { }

    /** Searches the network for about two seconds and returns the Sonos groups found. */
    static List<Group> discover(Context ctx) throws IOException {
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wifi.createMulticastLock("ipod-sonos");
        lock.acquire();
        Set<String> hosts = new LinkedHashSet<>();
        try (MulticastSocket socket = new MulticastSocket()) {
            byte[] msg = SEARCH.getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            socket.setSoTimeout(400);
            long end = System.currentTimeMillis() + 2200;
            int sent = 0;
            while (System.currentTimeMillis() < end) {
                if (sent < 3) {
                    socket.send(new DatagramPacket(msg, msg.length, group, 1900));
                    sent++;
                }
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!reply.toLowerCase(Locale.ROOT).contains("sonos")) continue;
                hosts.add(p.getAddress().getHostAddress() + ":1400");
            }
        } finally {
            lock.release();
        }
        for (String host : hosts) {
            try {
                return groups(host);
            } catch (IOException | XmlPullParserException ignored) {
                // Try the next speaker.
            }
        }
        return new ArrayList<>();
    }

    /** Asks one speaker for the household's groups, named after their rooms. */
    private static List<Group> groups(String host) throws IOException, XmlPullParserException {
        String reply = soap(host, "/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupState", "");
        String state = value(reply, "ZoneGroupState");
        if (state == null) throw new IOException("No groups");
        List<Group> out = new ArrayList<>();
        XmlPullParser x = Xml.newPullParser();
        x.setInput(new StringReader(state));
        String coordinator = null, coordinatorName = null, coordinatorHost = null;
        int others = 0;
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG && x.getName().equals("ZoneGroup")) {
                coordinator = x.getAttributeValue(null, "Coordinator");
                coordinatorName = coordinatorHost = null;
                others = 0;
            } else if (ev == XmlPullParser.START_TAG && x.getName().equals("ZoneGroupMember")) {
                // Hidden members are surround speakers and subs; a Boost has no sound at all.
                if ("1".equals(x.getAttributeValue(null, "Invisible"))
                        || "1".equals(x.getAttributeValue(null, "IsZoneBridge"))) continue;
                String uuid = x.getAttributeValue(null, "UUID");
                if (uuid != null && uuid.equals(coordinator)) {
                    coordinatorName = x.getAttributeValue(null, "ZoneName");
                    URL location = new URL(x.getAttributeValue(null, "Location"));
                    coordinatorHost = location.getHost() + ":" + location.getPort();
                } else {
                    others++;
                }
            } else if (ev == XmlPullParser.END_TAG && x.getName().equals("ZoneGroup")) {
                if (coordinatorName != null) {
                    String name = others > 0 ? coordinatorName + " +" + others : coordinatorName;
                    out.add(new Group(name, coordinatorHost, coordinator));
                }
            }
        }
        out.sort((a, b) -> MusicLibrary.NAME_ORDER.compare(a.name, b.name));
        return out;
    }

    // ---- SOAP --------------------------------------------------------------------------------

    /** Calls a Sonos control action and returns the reply's XML. */
    static String soap(String host, String path, String service, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"urn:schemas-upnp-org:service:" + service + ":1\">" + args
                + "</u:" + action + "></s:Body></s:Envelope>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        // A plain socket, not HttpURLConnection: Android blocks unencrypted HTTP for the app, and
        // Sonos only speaks plain HTTP. This keeps that block for everything else.
        int colon = host.lastIndexOf(':');
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host.substring(0, colon), Integer.parseInt(host.substring(colon + 1))), 3000);
            socket.setSoTimeout(5000);
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                    + "SOAPACTION: \"urn:schemas-upnp-org:service:" + service + ":1#" + action + "\"\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(b);
            out.flush();
            String reply = read(socket.getInputStream());
            int split = reply.indexOf("\r\n\r\n");
            if (!reply.startsWith("HTTP/") || split < 0) throw new IOException("Sonos didn't answer");
            String status = reply.substring(0, reply.indexOf("\r\n"));
            String headers = reply.substring(0, split).toLowerCase(Locale.ROOT);
            String content = reply.substring(split + 4);
            if (headers.contains("transfer-encoding: chunked")) content = unchunk(content);
            if (!status.contains(" 200 ")) throw new IOException("Sonos error: " + status);
            return content;
        }
    }

    private static String unchunk(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int eol = s.indexOf("\r\n", i);
            if (eol < 0) break;
            int size;
            try {
                size = Integer.parseInt(s.substring(i, eol).split(";")[0].trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) break;
            out.append(s, eol + 2, Math.min(s.length(), eol + 2 + size));
            i = eol + 2 + size + 2;
        }
        return out.toString();
    }

    /** The text of the first element with this name in a SOAP reply (unescaped). */
    static String value(String xml, String tag) {
        try {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(new StringReader(xml));
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG && x.getName().equals(tag)) return x.nextText();
            }
        } catch (XmlPullParserException | IOException ignored) { }
        return null;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
