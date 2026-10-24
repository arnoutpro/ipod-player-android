package com.arnoutpro.ipod3g;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Signs in to Spotify (authorization code with PKCE, so no client secret in the app) and talks to
 * the Web API on a background thread.
 */
public final class Spotify {
    // Not a secret: PKCE apps identify themselves with the client ID alone.
    static final String CLIENT_ID = "ee4a00a1e2684cf3938bdbd94f6798dc";
    public static final String REDIRECT = "ipod3g://spotify-callback";
    private static final String SCOPES = "user-read-private playlist-read-private playlist-read-collaborative "
            + "user-library-read user-follow-read user-read-playback-state user-modify-playback-state "
            + "user-read-currently-playing "
            // Lets the App Remote SDK wake the Spotify app without its own authorization screen.
            + "app-remote-control";
    private static final String API = "https://api.spotify.com/v1";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    /** Stops runaway paging through enormous libraries. */
    private static final int MAX_ITEMS = 2000;

    public interface Work<T> { T run() throws IOException, JSONException; }

    public interface Done<T> { void done(T result, String error); }

    /** Receives each page of a paged list as it arrives, on the background thread. */
    public interface Pages { void page(List<JSONObject> items) throws JSONException; }

    /** An HTTP error from Spotify, with its message shortened for the LCD. */
    public static final class ApiException extends IOException {
        public final int status;
        public final String reason;
        /** Seconds Spotify asks to wait after "too many requests". */
        int retryAfter;

        ApiException(int status, String message, String reason) {
            super(message);
            this.status = status;
            this.reason = reason;
        }
    }

    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String accessToken;
    private long expiresAt;

    public Spotify(SharedPreferences prefs) { this.prefs = prefs; }

    public boolean connected() { return prefs.contains("spRefresh"); }

    /** The signed-in user's name, once known. */
    public String userName() { return prefs.getString("spName", ""); }

    public String userId() { return prefs.getString("spUser", ""); }

    // ---- sign in ---------------------------------------------------------------------------

    /** Opens Spotify's login page in the browser; it comes back through {@link #REDIRECT}. */
    public void login(Activity a) {
        String verifier = random(64);
        String state = random(16);
        prefs.edit().putString("spVerifier", verifier).putString("spState", state).apply();
        Uri uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge(verifier))
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("state", state)
                .build();
        a.startActivity(new Intent(Intent.ACTION_VIEW, uri));
    }

    /** Finishes signing in with the code from the redirect. */
    public void finishLogin(Uri redirect, Done<Void> done) {
        String code = redirect.getQueryParameter("code");
        String state = redirect.getQueryParameter("state");
        String verifier = prefs.getString("spVerifier", null);
        if (code == null || verifier == null || state == null || !state.equals(prefs.getString("spState", ""))) {
            String err = redirect.getQueryParameter("error");
            done.done(null, err != null ? "Login cancelled" : "Login failed");
            return;
        }
        call(() -> {
            JSONObject t = postToken("grant_type=authorization_code&code=" + enc(code)
                    + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + CLIENT_ID
                    + "&code_verifier=" + enc(verifier));
            storeToken(t);
            JSONObject me = get("/me");
            prefs.edit().putString("spName", me.optString("display_name", me.optString("id")))
                    .putString("spUser", me.optString("id"))
                    .remove("spVerifier").remove("spState").apply();
            return null;
        }, done);
    }

    /** Forgets the account and every token, as Spotify's terms require on disconnect. */
    public void logout() {
        accessToken = null;
        expiresAt = 0;
        prefs.edit().remove("spRefresh").remove("spName").remove("spUser")
                .remove("spVerifier").remove("spState").apply();
    }

    // ---- requests --------------------------------------------------------------------------

    /** Runs work on the background thread and reports back on the main thread. */
    public <T> void call(Work<T> work, Done<T> done) {
        io.execute(() -> {
            T result = null;
            String error = null;
            try {
                result = work.run();
            } catch (IOException e) {
                error = e.getMessage() != null ? e.getMessage() : "No connection";
            } catch (JSONException e) {
                error = "Unexpected reply";
            }
            T r = result;
            String err = error;
            if (done != null) main.post(() -> done.done(r, err));
        });
    }

    /** GET a Web API path (or a full "next" URL); on the background thread. */
    public JSONObject get(String path) throws IOException, JSONException {
        String body = api("GET", path, null);
        return body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    /** PUT or POST a player command; on the background thread. */
    public void send(String method, String path, String json) throws IOException {
        api(method, path, json);
    }

    /**
     * Follows "next" links and gathers every item of a paged list. Paths leave out "limit":
     * Spotify's default page size is always allowed, while its maximum changes over time.
     */
    public List<JSONObject> all(String path, String wrapper) throws IOException, JSONException {
        return all(path, wrapper, null);
    }

    public List<JSONObject> all(String path, String wrapper, Pages pages) throws IOException, JSONException {
        return all(path, wrapper, MAX_ITEMS, pages);
    }

    public List<JSONObject> all(String path, String wrapper, int max, Pages pages) throws IOException, JSONException {
        List<JSONObject> out = new ArrayList<>();
        String next = path;
        while (next != null && out.size() < max) {
            JSONObject page = get(next);
            if (wrapper != null) page = page.getJSONObject(wrapper);
            JSONArray items = page.optJSONArray("items");
            List<JSONObject> got = new ArrayList<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o != null) got.add(o);
            }
            out.addAll(got);
            if (pages != null) pages.page(got);
            next = page.isNull("next") ? null : page.optString("next", null);
        }
        return out;
    }

    private String api(String method, String path, String json) throws IOException {
        String url = path.startsWith("http") ? path : API + path;
        try {
            return http(method, url, json, "application/json", token());
        } catch (ApiException e) {
            if (e.status == 401) {
                accessToken = null; // expired early: refresh once and try again
            } else if (e.status == 429 && e.retryAfter <= 30) {
                // Loading a big library can hit Spotify's rate limit: wait as asked, then go on.
                try {
                    Thread.sleep(Math.max(1, e.retryAfter) * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            } else {
                throw e;
            }
            return http(method, url, json, "application/json", token());
        }
    }

    private synchronized String token() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 60_000) return accessToken;
        String refresh = prefs.getString("spRefresh", null);
        if (refresh == null) throw new IOException("Not connected");
        try {
            storeToken(postToken("grant_type=refresh_token&refresh_token=" + enc(refresh)
                    + "&client_id=" + CLIENT_ID));
        } catch (ApiException e) {
            if (e.status == 400) logout(); // the refresh token was revoked or ran out
            throw new IOException(e.status == 400 ? "Please connect again" : e.getMessage());
        }
        return accessToken;
    }

    private JSONObject postToken(String form) throws IOException {
        String body = http("POST", TOKEN_URL, form, "application/x-www-form-urlencoded", null);
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Unexpected reply");
        }
    }

    private void storeToken(JSONObject t) {
        accessToken = t.optString("access_token");
        expiresAt = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000;
        String refresh = t.optString("refresh_token", "");
        if (!refresh.isEmpty()) prefs.edit().putString("spRefresh", refresh).apply();
    }

    private static String http(String method, String url, String body, String type, String bearer)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null || method.equals("PUT") || method.equals("POST")) {
                byte[] b = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", type);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream out = c.getOutputStream()) { out.write(b); }
            }
            int status = c.getResponseCode();
            InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
            String text = in == null ? "" : read(in);
            if (status >= 400) {
                ApiException e = error(status, text);
                try {
                    e.retryAfter = Integer.parseInt(c.getHeaderField("Retry-After"));
                } catch (NumberFormatException ignored) { }
                throw e;
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static ApiException error(int status, String text) {
        String message = null, reason = "";
        try {
            JSONObject o = new JSONObject(text);
            Object e = o.opt("error");
            if (e instanceof JSONObject) {
                message = ((JSONObject) e).optString("message", null);
                reason = ((JSONObject) e).optString("reason", "");
            } else if (e instanceof String) {
                reason = (String) e;
                message = o.optString("error_description", (String) e);
            }
        } catch (JSONException ignored) { }
        if (reason.equals("PREMIUM_REQUIRED")) message = "Premium required";
        else if (reason.equals("NO_ACTIVE_DEVICE")) message = "Open Spotify first";
        else if (status == 403) message = "Not allowed";
        else if (status == 429) message = "Too many requests";
        else if (message == null || message.isEmpty()) message = "Spotify error " + status;
        return new ApiException(status, message, reason);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    // ---- helpers ---------------------------------------------------------------------------

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int n) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append(chars.charAt(r.nextInt(chars.length())));
        return s.toString();
    }

    private static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
