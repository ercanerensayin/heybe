package com.ercan.heybe.tools.perde

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** OpenSubtitles.com REST API: altyazı arama ve indirme (kullanıcının kendi API anahtarıyla). */
object OpenSubs {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private const val UA = "Heybe v1.0"

    data class Hit(val fileId: Long, val release: String, val language: String, val downloads: Int, val fileName: String?)
    class SubError(msg: String) : Exception(msg)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("opensubtitles", Context.MODE_PRIVATE)
    fun apiKey(ctx: Context) = sp(ctx).getString("api", "").orEmpty()
    fun user(ctx: Context) = sp(ctx).getString("user", "").orEmpty()
    fun hasPassword(ctx: Context) = sp(ctx).getString("pass", "").orEmpty().isNotEmpty()
    fun save(ctx: Context, api: String, user: String, pass: String?) {
        sp(ctx).edit().putString("api", api.trim()).putString("user", user.trim())
            .apply { if (pass != null) putString("pass", pass) }.remove("token").apply()
    }

    private fun conn(ctx: Context, url: String, method: String = "GET", token: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Api-Key", apiKey(ctx)); setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun HttpURLConnection.body(): String = (if (responseCode in 200..299) inputStream else errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    private fun fail(code: Int, body: String): Nothing {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        throw SubError(when (code) {
            401, 403 -> "API anahtarı ya da kullanıcı bilgileri geçersiz. ${msg}".trim()
            406, 429 -> "Günlük indirme ya da istek sınırı doldu. ${msg}".trim()
            else -> "OpenSubtitles hatası ($code) ${msg}".trim()
        })
    }

    private fun post(ctx: Context, path: String, json: JSONObject, token: String?): JSONObject {
        val c = conn(ctx, "$BASE$path", "POST", token)
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toString().toByteArray()) }
        val b = c.body(); if (c.responseCode !in 200..299) fail(c.responseCode, b)
        return JSONObject(b)
    }

    private fun token(ctx: Context): String? {
        val u = user(ctx); val p = sp(ctx).getString("pass", "").orEmpty()
        if (u.isEmpty() || p.isEmpty()) return null
        sp(ctx).getString("token", null)?.let { return it }
        val t = post(ctx, "/login", JSONObject().put("username", u).put("password", p), null).optString("token").ifEmpty { null }
        if (t != null) sp(ctx).edit().putString("token", t).apply()
        return t
    }

    suspend fun search(ctx: Context, query: String, lang: String): List<Hit> = withContext(Dispatchers.IO) {
        if (apiKey(ctx).isEmpty()) throw SubError("Önce Ayarlar → Altyazı (OpenSubtitles) bölümüne API anahtarını gir.")
        val c = conn(ctx, "$BASE/subtitles?query=${URLEncoder.encode(query.lowercase(), "UTF-8")}&languages=$lang&order_by=download_count")
        val b = c.body(); if (c.responseCode !in 200..299) fail(c.responseCode, b)
        val data = JSONObject(b).optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).mapNotNull { i ->
            val a = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            Hit(f.optLong("file_id"), a.optString("release").ifEmpty { f.optString("file_name") }, a.optString("language"),
                a.optInt("download_count"), f.optString("file_name").ifEmpty { null })
        }
    }

    /** İndirir ve uygulama klasörüne kaydeder; dosyayı ve kalan günlük hakkı döndürür. */
    suspend fun download(ctx: Context, hit: Hit): Pair<File, Int?> = withContext(Dispatchers.IO) {
        val tok = runCatching { token(ctx) }.getOrNull()
        val r = post(ctx, "/download", JSONObject().put("file_id", hit.fileId), tok)
        val link = r.optString("link").ifEmpty { throw SubError("İndirme bağlantısı alınamadı.") }
        val bytes = (URL(link).openConnection() as HttpURLConnection).run { setRequestProperty("User-Agent", UA); inputStream.use { it.readBytes() } }
        val dir = File(ctx.filesDir, "altyazilar").apply { mkdirs() }
        var name = (hit.fileName ?: r.optString("file_name").ifEmpty { "altyazi_${hit.fileId}" }).replace('/', '_')
        if (com.ercan.heybe.core.SubtitleText.formatOf(name) == null) name += ".srt"
        val f = File(dir, name); f.writeBytes(bytes)
        f to (if (r.has("remaining")) r.optInt("remaining") else null)
    }
}
