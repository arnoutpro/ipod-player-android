package com.arnoutpro.ipod3g;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

/**
 * Lets a Chromecast fetch the current song from this phone over Wi-Fi: a tiny HTTP server with
 * range requests for seeking. Only the current song is served, behind a random path, so nobody
 * else on the network can browse the phone's music.
 */
final class MusicServer {
    private final ContentResolver resolver;
    private final String token;
    private ServerSocket server;
    private volatile Uri song;
    private volatile String songPath, songType;

    MusicServer(ContentResolver resolver) {
        this.resolver = resolver;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        token = s.toString();
    }

    /** Serves this song from now on; returns the address the Chromecast can fetch it from. */
    String publish(Track t) throws IOException {
        if (server == null) start();
        String ip = wifiAddress();
        if (ip == null) throw new IOException("No Wi-Fi");
        song = t.uri;
        songType = mimeType(t.uri);
        songPath = "/" + token + "/" + t.id;
        return "http://" + ip + ":" + server.getLocalPort() + songPath;
    }

    String type() { return songType; }

    void stop() {
        song = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) { }
        server = null;
    }

    private void start() throws IOException {
        ServerSocket s = new ServerSocket(0);
        server = s;
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    new Thread(() -> serve(c), "music-request").start();
                } catch (IOException e) {
                    return; // closed
                }
            }
        }, "music-server");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket c) {
        try (Socket socket = c) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String request = in.readLine();
            if (request == null) return;
            String range = null;
            for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
                if (h.toLowerCase(Locale.ROOT).startsWith("range:")) range = h.substring(6).trim();
            }
            String[] parts = request.split(" ");
            OutputStream out = socket.getOutputStream();
            Uri uri = song;
            if (parts.length < 2 || uri == null || !parts[1].equals(songPath)) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                return;
            }
            try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
                 FileInputStream file = new FileInputStream(pfd.getFileDescriptor())) {
                long size = pfd.getStatSize();
                long start = 0, end = size - 1;
                boolean partial = false;
                if (range != null && range.startsWith("bytes=")) {
                    String[] r = range.substring(6).split("-", 2);
                    try {
                        if (!r[0].isEmpty()) start = Long.parseLong(r[0]);
                        if (r.length > 1 && !r[1].isEmpty()) end = Math.min(end, Long.parseLong(r[1]));
                        else if (r[0].isEmpty() && r.length > 1) start = Math.max(0, size - Long.parseLong(r[1]));
                        partial = true;
                    } catch (NumberFormatException ignored) { }
                }
                long length = Math.max(0, end - start + 1);
                StringBuilder head = new StringBuilder();
                head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                        .append("Content-Type: ").append(songType).append("\r\n")
                        .append("Content-Length: ").append(length).append("\r\n")
                        .append("Accept-Ranges: bytes\r\n")
                        .append("Access-Control-Allow-Origin: *\r\n")
                        .append("Connection: close\r\n");
                if (partial) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
                head.append("\r\n");
                out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (parts[0].equals("HEAD")) return;
                file.getChannel().position(start);
                byte[] buf = new byte[64 * 1024];
                long left = length;
                while (left > 0) {
                    int n = file.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
                out.flush();
            }
        } catch (IOException ignored) {
            // The Chromecast hung up, e.g. to seek: normal.
        }
    }

    private String mimeType(Uri uri) {
        String t = resolver.getType(uri);
        return t != null && t.startsWith("audio/") ? t : "audio/mpeg";
    }

    /** This phone's address on the local network, preferring Wi-Fi. */
    private static String wifiAddress() {
        String any = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                    if (any == null) any = a.getHostAddress();
                }
            }
        } catch (IOException ignored) { }
        return any;
    }
}
