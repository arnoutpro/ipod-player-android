package com.arnoutpro.ipod3g

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Signs in to Spotify (authorization code with PKCE, so no client secret in the app) and talks to
 * the Web API on a background thread.
 */
class Spotify(private val prefs: SharedPreferences) {

    fun interface Work<T> {
        @Throws(IOException::class, JSONException::class)
        fun run(): T
    }

    fun interface Done<T> {
        fun done(result: T?, error: String?)
    }

    /** Receives each page of a paged list as it arrives, on the background thread. */
    fun interface Pages {
        @Throws(JSONException::class)
        fun page(items: List<JSONObject>)
    }

    /** An HTTP error from Spotify, with its message shortened for the LCD. */
    class ApiException internal constructor(
        val status: Int,
        message: String,
        val reason: String,
    ) : IOException(message) {
        /** Seconds Spotify asks to wait after "too many requests". */
        internal var retryAfter = 0
    }

    private val scope = MainScope()

    // One at a time, in order, like the single threads they replace. Long jobs like loading the
    // whole library get their own line, so they never hold up player commands.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val bulk: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt = 0L

    fun connected() = prefs.contains("spRefresh")

    /** The signed-in user's name, once known. */
    fun userName(): String = prefs.getString("spName", "")!!

    fun userId(): String = prefs.getString("spUser", "")!!

    // ---- sign in ---------------------------------------------------------------------------

    /** Opens Spotify's login page in the browser; it comes back through [REDIRECT]. */
    fun login(a: Activity) {
        val verifier = random(64)
        val state = random(16)
        prefs.edit().putString("spVerifier", verifier).putString("spState", state).apply()
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build()
        a.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /** Finishes signing in with the code from the redirect. */
    fun finishLogin(redirect: Uri, done: Done<Void>) {
        val code = redirect.getQueryParameter("code")
        val state = redirect.getQueryParameter("state")
        val verifier = prefs.getString("spVerifier", null)
        if (code == null || verifier == null || state == null || state != prefs.getString("spState", "")) {
            val err = redirect.getQueryParameter("error")
            done.done(null, if (err != null) "Login cancelled" else "Login failed")
            return
        }
        call<Void?>({
            val t = postToken(
                "grant_type=authorization_code&code=" + enc(code) +
                    "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + CLIENT_ID +
                    "&code_verifier=" + enc(verifier)
            )
            storeToken(t)
            val me = get("/me")
            prefs.edit().putString("spName", me.optString("display_name", me.optString("id")))
                .putString("spUser", me.optString("id"))
                .remove("spVerifier").remove("spState").apply()
            null
        }, { _, err -> done.done(null, err) })
    }

    /** Forgets the account and every token, as Spotify's terms require on disconnect. */
    fun logout() {
        accessToken = null
        expiresAt = 0
        prefs.edit().remove("spRefresh").remove("spName").remove("spUser")
            .remove("spVerifier").remove("spState").apply()
    }

    // ---- requests --------------------------------------------------------------------------

    /** Runs work on the background thread and reports back on the main thread. */
    fun <T> call(work: Work<T>, done: Done<T>?) = run(io, work, done)

    /** Like [call] but on a line of its own, for work that takes minutes. */
    fun <T> callLong(work: Work<T>, done: Done<T>?) = run(bulk, work, done)

    private fun <T> run(on: CoroutineDispatcher, work: Work<T>, done: Done<T>?) {
        scope.launch {
            var result: T? = null
            var error: String? = null
            try {
                result = withContext(on) { work.run() }
            } catch (e: IOException) {
                error = e.message ?: "No connection"
            } catch (e: JSONException) {
                error = "Unexpected reply"
            }
            done?.done(result, error)
        }
    }

    /** GET a Web API path (or a full "next" URL); on a background thread. */
    @Throws(IOException::class, JSONException::class)
    fun get(path: String): JSONObject {
        val body = api("GET", path, null)
        return if (body.isEmpty()) JSONObject() else JSONObject(body)
    }

    /** PUT or POST a player command; on a background thread. */
    @Throws(IOException::class)
    fun send(method: String, path: String, json: String?) {
        api(method, path, json)
    }

    /**
     * Follows "next" links and gathers every item of a paged list. Paths leave out "limit":
     * Spotify's default page size is always allowed, while its maximum changes over time.
     */
    @Throws(IOException::class, JSONException::class)
    fun all(path: String, wrapper: String?, pages: Pages? = null): List<JSONObject> =
        all(path, wrapper, MAX_ITEMS, pages)

    @Throws(IOException::class, JSONException::class)
    fun all(path: String, wrapper: String?, max: Int, pages: Pages?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var next: String? = path
        while (next != null && out.size < max) {
            var page = get(next)
            if (wrapper != null) page = page.getJSONObject(wrapper)
            val items = page.optJSONArray("items")
            val got = ArrayList<JSONObject>()
            for (i in 0 until (items?.length() ?: 0)) items!!.optJSONObject(i)?.let { got.add(it) }
            out.addAll(got)
            pages?.page(got)
            next = page.stringOrNull("next")
        }
        return out
    }

    private fun api(method: String, path: String, json: String?): String {
        val url = if (path.startsWith("http")) path else API + path
        try {
            return http(method, url, json, "application/json", token())
        } catch (e: ApiException) {
            when {
                e.status == 401 -> accessToken = null // expired early: refresh once and try again
                e.status == 429 && e.retryAfter <= 30 ->
                    // Loading a big library can hit Spotify's rate limit: wait as asked, then go on.
                    try {
                        Thread.sleep(maxOf(1, e.retryAfter) * 1000L)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                else -> throw e
            }
            return http(method, url, json, "application/json", token())
        }
    }

    @Synchronized
    private fun token(): String {
        accessToken?.let { if (System.currentTimeMillis() < expiresAt - 60_000) return it }
        val refresh = prefs.getString("spRefresh", null) ?: throw IOException("Not connected")
        try {
            storeToken(postToken("grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + CLIENT_ID))
        } catch (e: ApiException) {
            if (e.status == 400) logout() // the refresh token was revoked or ran out
            throw IOException(if (e.status == 400) "Please connect again" else e.message)
        }
        return accessToken ?: throw IOException("Not connected")
    }

    private fun postToken(form: String): JSONObject {
        val body = http("POST", TOKEN_URL, form, "application/x-www-form-urlencoded", null)
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("Unexpected reply")
        }
    }

    private fun storeToken(t: JSONObject) {
        accessToken = t.optString("access_token")
        expiresAt = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000
        val refresh = t.optString("refresh_token", "")
        if (refresh.isNotEmpty()) prefs.edit().putString("spRefresh", refresh).apply()
    }

    companion object {
        // Not a secret: PKCE apps identify themselves with the client ID alone.
        const val CLIENT_ID = "ee4a00a1e2684cf3938bdbd94f6798dc"
        const val REDIRECT = "ipod3g://spotify-callback"
        private const val SCOPES = "user-read-private playlist-read-private playlist-read-collaborative " +
            "user-library-read user-follow-read user-read-playback-state user-modify-playback-state " +
            "user-read-currently-playing " +
            // Lets the App Remote SDK wake the Spotify app without its own authorization screen.
            "app-remote-control"
        private const val API = "https://api.spotify.com/v1"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        /** Stops runaway paging through enormous libraries. */
        private const val MAX_ITEMS = 2000

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        /** The value as a string, or null when it's missing or JSON null. */
        internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

        private fun http(method: String, url: String, body: String?, type: String, bearer: String?): String {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null || method == "PUT" || method == "POST") {
                    val b = (body ?: "").toByteArray(Charsets.UTF_8)
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", type)
                    c.setFixedLengthStreamingMode(b.size)
                    c.outputStream.use { it.write(b) }
                }
                val status = c.responseCode
                val stream: InputStream? = if (status < 400) c.inputStream else c.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                if (status >= 400) {
                    val e = error(status, text)
                    e.retryAfter = c.getHeaderField("Retry-After")?.toIntOrNull() ?: 0
                    throw e
                }
                return text
            } finally {
                c.disconnect()
            }
        }

        private fun error(status: Int, text: String): ApiException {
            var message: String? = null
            var reason = ""
            try {
                val o = JSONObject(text)
                when (val e = o.opt("error")) {
                    is JSONObject -> {
                        message = e.stringOrNull("message")
                        reason = e.optString("reason", "")
                    }
                    is String -> {
                        reason = e
                        message = o.optString("error_description", e)
                    }
                }
            } catch (ignored: JSONException) {
            }
            message = when {
                reason == "PREMIUM_REQUIRED" -> "Premium required"
                reason == "NO_ACTIVE_DEVICE" -> "Open Spotify first"
                status == 403 -> "Not allowed"
                status == 429 -> "Too many requests"
                message.isNullOrEmpty() -> "Spotify error $status"
                else -> message
            }
            return ApiException(status, message, reason)
        }

        private fun random(n: Int): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val r = SecureRandom()
            return String(CharArray(n) { chars[r.nextInt(chars.length)] })
        }

        private fun challenge(verifier: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}
