package com.ercan.heybe.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ayarlar, favoriler, siteler, geçmiş ve izleme kayıtlarının JSON yedeği. Şifreler yedeğe girmez. */
object Backup {
    private val prefNames = listOf("heybe", "siteler", "perde", "opensubtitles")
    private val secret = setOf("pass", "token")

    suspend fun export(ctx: Context): FileStore.Saved = withContext(Dispatchers.IO) {
        val root = JSONObject().put("uygulama", "Heybe").put("surum", 1).put("tarih", System.currentTimeMillis())
        val prefs = JSONObject()
        for (n in prefNames) {
            val o = JSONObject()
            ctx.getSharedPreferences(n, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (k in secret) return@forEach
                o.put(k, when (v) {
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    else -> JSONObject().put("t", "s").put("v", v.toString())
                })
            }
            prefs.put(n, o)
        }
        root.put("tercihler", prefs)
        val db = HeybeDb.get(ctx)
        root.put("gecmis", JSONArray(db.history().list().filter { it.status == Status.DONE }.map {
            JSONObject().put("time", it.time).put("toolId", it.toolId).put("title", it.title).put("uri", it.uri).put("path", it.path)
                .put("mime", it.mime).put("size", it.size)
        }))
        root.put("izleme", JSONArray(db.playback().list().map {
            JSONObject().put("key", it.key).put("title", it.title).put("position", it.position).put("duration", it.duration)
                .put("lastPlayed", it.lastPlayed).put("watched", it.watched).put("subUri", it.subUri).put("subOffsetMs", it.subOffsetMs)
                .put("audioLang", it.audioLang).put("textLang", it.textLang).put("textOff", it.textOff)
        }))
        val name = "heybe_yedek_" + SimpleDateFormat("ddMMyyyy_HHmm", Locale.ROOT).format(Date()) + ".json"
        val saved = FileStore.save(ctx, FileStore.Folder.YEDEK, name, "application/json") { it.write(root.toString(2).toByteArray()) }
        Recorder.done(ctx, "yedek", saved, "application/json")
        saved
    }

    private fun JSONObject.optStr(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

    /** Yedeği geri yükler; özet metin döndürür. */
    suspend fun import(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Dosya açılamadı")
        val root = JSONObject(text)
        if (root.optString("uygulama") != "Heybe") error("Bu bir Heybe yedeği değil.")
        root.optJSONObject("tercihler")?.let { prefs ->
            for (n in prefNames) {
                val o = prefs.optJSONObject(n) ?: continue
                val ed = ctx.getSharedPreferences(n, Context.MODE_PRIVATE).edit()
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    when (e.optString("t")) {
                        "set" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                        "b" -> ed.putBoolean(k, e.getBoolean("v")); "l" -> ed.putLong(k, e.getLong("v"))
                        "i" -> ed.putInt(k, e.getInt("v")); "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                        else -> ed.putString(k, e.optString("v"))
                    }
                }
                ed.apply()
            }
        }
        val db = HeybeDb.get(ctx)
        val existing = db.history().list().map { it.time to it.uri }.toSet()
        var h = 0
        root.optJSONArray("gecmis")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val key = o.getLong("time") to o.optStr("uri")
                if (key in existing) continue
                db.history().insert(HistoryItem(time = o.getLong("time"), toolId = o.getString("toolId"), title = o.getString("title"),
                    uri = o.optStr("uri"), path = o.optStr("path"), mime = o.optString("mime", "application/octet-stream"), size = o.optLong("size")))
                h++
            }
        }
        var p = 0
        root.optJSONArray("izleme")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                db.playback().upsert(Playback(o.getString("key"), o.getString("title"), o.optLong("position"), o.optLong("duration"),
                    o.optLong("lastPlayed"), o.optBoolean("watched"), o.optStr("subUri"), o.optLong("subOffsetMs"),
                    o.optStr("audioLang"), o.optStr("textLang"), o.optBoolean("textOff")))
                p++
            }
        }
        "Geri yüklendi: ayarlar, $h geçmiş kaydı, $p video kaydı. Favorilerin görünmesi için Heybe'yi kapatıp aç."
    }
}
