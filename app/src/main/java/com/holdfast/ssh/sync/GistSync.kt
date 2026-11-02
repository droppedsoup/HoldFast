package com.holdfast.ssh.sync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Stores an already-encrypted vault blob in a private GitHub gist. GitHub never sees plaintext. */
object GistSync {
    private const val FILE = "holdfast.vault"

    private fun call(method: String, url: String, token: String, body: JSONObject?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method // Android's HttpURLConnection (OkHttp-backed) supports PATCH
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000; c.readTimeout = 30000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) throw RuntimeException("GitHub $code: ${text.take(200)}")
        return JSONObject(text)
    }

    /** Returns the gist id. */
    fun push(token: String, gistId: String?, blob: ByteArray): String {
        val files = JSONObject().put(FILE, JSONObject().put("content", Base64.getEncoder().encodeToString(blob)))
        return if (gistId == null) {
            call("POST", "https://api.github.com/gists", token,
                JSONObject().put("description", "HoldFast encrypted vault").put("public", false).put("files", files)).getString("id")
        } else {
            call("PATCH", "https://api.github.com/gists/$gistId", token, JSONObject().put("files", files)).getString("id")
        }
    }

    fun pull(token: String, gistId: String): ByteArray {
        val g = call("GET", "https://api.github.com/gists/$gistId", token, null)
        val f = g.getJSONObject("files").getJSONObject(FILE)
        val content = if (f.optBoolean("truncated")) {
            URL(f.getString("raw_url")).openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        } else f.getString("content")
        return Base64.getDecoder().decode(content.trim())
    }
}
