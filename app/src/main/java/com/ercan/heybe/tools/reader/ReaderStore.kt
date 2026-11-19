package com.ercan.heybe.tools.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ercan.heybe.core.doc.Block
import com.ercan.heybe.core.doc.Doc
import com.ercan.heybe.core.doc.DocReaders
import com.ercan.heybe.core.doc.Run
import com.ercan.heybe.core.doc.Udf
import com.ercan.heybe.core.doc.plain
import com.ercan.heybe.tools.convert.ConvertEngine
import com.ercan.heybe.tools.convert.Kind
import com.ercan.heybe.tools.pdf.PdfEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ReaderError(message: String) : Exception(message)

/** Okuyucu: belgeyi açar, okunmuş halini önbelleğe yazar, son okunanları ve kalınan yeri tutar. */
object ReaderStore {
    data class Recent(val uri: String, val title: String, val percent: Int, val time: Long)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("okuyucu", Context.MODE_PRIVATE)

    val textExt = setOf("txt", "md", "markdown", "text", "log", "csv")

    fun supported(name: String, mime: String?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in textExt || mime?.startsWith("text/") == true) return true
        return ConvertEngine.kindOf(name, mime) in setOf(Kind.PDF, Kind.DOCX, Kind.EPUB, Kind.UDF)
    }

    private fun meta(ctx: Context, uri: Uri): Pair<String, Long> = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) Pair(it.getString(0) ?: "belge", if (it.isNull(1)) -1L else it.getLong(1)) else null
        }
    }.getOrNull() ?: Pair(PdfEngine.displayName(ctx, uri), -1L)

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    fun keyOf(ctx: Context, uri: Uri): String { val (n, size) = meta(ctx, uri); return hash("$uri|$n|$size") }

    private fun cacheDir(ctx: Context, key: String) = File(ctx.filesDir, "okuyucu/$key")

    /** Belgeyi okur (önbellekte varsa oradan). */
    fun load(ctx: Context, uri: Uri, progress: (String) -> Unit): Pair<String, Doc> {
        val (name, _) = meta(ctx, uri)
        val key = keyOf(ctx, uri)
        val dir = cacheDir(ctx, key)
        runCatching { readCache(dir) }.getOrNull()?.let { return key to it }
        val mime = ctx.contentResolver.getType(uri)
        if (!supported(name, mime)) throw ReaderError("Bu dosya okunamıyor. EPUB, PDF, Word, UDF, TXT ya da MD seç.")
        progress("Dosya açılıyor")
        val tmp = File(ctx.cacheDir, "okuyucu_girdi").apply { deleteRecursively(); mkdirs() }
        val f = File(tmp, "g." + name.substringAfterLast('.', "bin"))
        (ctx.contentResolver.openInputStream(uri) ?: throw ReaderError("Dosya açılamadı.")).use { i -> f.outputStream().use { i.copyTo(it) } }
        val title = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").lowercase()
        val doc = try {
            when {
                ext in textExt || (mime?.startsWith("text/") == true && ConvertEngine.kindOf(name, mime) == null) -> textDoc(title, f.readText())
                else -> when (ConvertEngine.kindOf(name, mime)) {
                    Kind.PDF -> ConvertEngine.pdfToDoc(ctx, f, title) { p, m -> progress("$m (%$p)") }
                    Kind.DOCX -> DocReaders.docx(f, title, ConvertEngine.codec())
                    Kind.EPUB -> DocReaders.epub(f, title, ConvertEngine.codec())
                    Kind.UDF -> Udf.read(f, title, ConvertEngine.codec()).first
                    else -> textDoc(title, f.readText())
                }
            }
        } catch (e: ReaderError) { throw e } catch (e: Throwable) {
            throw ReaderError("Belge okunamadı: ${e.message ?: e.javaClass.simpleName}")
        } finally { tmp.deleteRecursively() }
        if (doc.blocks.none { it.plain()?.isNotBlank() == true || it is Block.Image }) throw ReaderError("Belgede okunacak metin bulunamadı.")
        runCatching { writeCache(dir, doc) }
        trimCache(ctx)
        return key to doc
    }

    fun textDoc(title: String, text: String): Doc {
        val blocks = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) blocks += Block.Para(para.toString().trim()); para.clear() }
        for (raw in text.replace("\r\n", "\n").lines()) {
            val l = raw.trimEnd()
            val h = Regex("^(#{1,6})\\s+(.+)$").find(l.trimStart())
            when {
                l.isBlank() -> flush()
                h != null -> { flush(); blocks += Block.Heading(h.groupValues[1].length.coerceAtMost(3), h.groupValues[2].trim()) }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l) }
            }
        }
        flush()
        return Doc(title, blocks)
    }

    // --- önbellek ---
    private fun writeCache(dir: File, doc: Doc) {
        dir.deleteRecursively(); dir.mkdirs()
        val arr = JSONArray()
        var img = 0
        for (b in doc.blocks) arr.put(when (b) {
            is Block.Heading -> JSONObject().put("t", "h").put("l", b.level).put("x", b.text)
            is Block.Para -> JSONObject().put("t", "p").put("x", b.text)
            is Block.Rich -> JSONObject().put("t", "r").put("a", b.align).put("r", JSONArray().apply {
                b.runs.forEach { r -> put(JSONArray().put(r.text).put(r.bold).put(r.italic).put(r.underline)) }
            })
            is Block.Table -> JSONObject().put("t", "tb").put("rows", JSONArray().apply { b.rows.forEach { put(JSONArray(it)) } })
            is Block.Image -> { val n = "i${img++}.jpg"; File(dir, n).writeBytes(b.jpeg); JSONObject().put("t", "i").put("f", n).put("w", b.width).put("h", b.height) }
        })
        File(dir, "belge.json").writeText(JSONObject().put("title", doc.title).put("blocks", arr).toString())
    }

    private fun readCache(dir: File): Doc? {
        val f = File(dir, "belge.json")
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("blocks")
        val blocks = (0 until arr.length()).mapNotNull { i ->
            val b = arr.getJSONObject(i)
            when (b.getString("t")) {
                "h" -> Block.Heading(b.getInt("l"), b.getString("x"))
                "p" -> Block.Para(b.getString("x"))
                "r" -> { val r = b.getJSONArray("r"); Block.Rich((0 until r.length()).map { j -> val x = r.getJSONArray(j); Run(x.getString(0), x.getBoolean(1), x.getBoolean(2), x.getBoolean(3)) }, b.optInt("a")) }
                "tb" -> { val rows = b.getJSONArray("rows"); Block.Table((0 until rows.length()).map { j -> val r = rows.getJSONArray(j); (0 until r.length()).map { r.getString(it) } }) }
                "i" -> File(dir, b.getString("f")).takeIf { it.exists() }?.let { Block.Image(it.readBytes(), b.getInt("w"), b.getInt("h")) }
                else -> null
            }
        }
        File(dir, "belge.json").setLastModified(System.currentTimeMillis())
        return Doc(o.optString("title", "Belge"), blocks)
    }

    /** En son açılan 15 belgenin önbelleği kalır. */
    private fun trimCache(ctx: Context) {
        val root = File(ctx.filesDir, "okuyucu")
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { File(it, "belge.json").lastModified() } ?: return
        dirs.drop(15).forEach { it.deleteRecursively() }
    }

    // --- kalınan yer ve son okunanlar ---
    fun position(ctx: Context, key: String): Pair<Int, Int> = sp(ctx).getString("pos_$key", null)?.split(',')
        ?.let { Pair(it[0].toIntOrNull() ?: 0, it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: Pair(0, 0)

    fun savePosition(ctx: Context, key: String, index: Int, offset: Int) { sp(ctx).edit().putString("pos_$key", "$index,$offset").apply() }

    fun recents(ctx: Context): List<Recent> = sp(ctx).getString("recent", "").orEmpty().lines().mapNotNull { l ->
        val p = l.split('\t'); if (p.size < 4) null else Recent(p[0], p[1], p[2].toIntOrNull() ?: 0, p[3].toLongOrNull() ?: 0)
    }

    fun touch(ctx: Context, uri: String, title: String, percent: Int) {
        val list = listOf(Recent(uri, title.replace('\t', ' ').replace('\n', ' '), percent, System.currentTimeMillis())) + recents(ctx).filter { it.uri != uri }
        sp(ctx).edit().putString("recent", list.take(12).joinToString("\n") { "${it.uri}\t${it.title}\t${it.percent}\t${it.time}" }).apply()
    }

    fun forget(ctx: Context, uri: String) {
        sp(ctx).edit().putString("recent", recents(ctx).filter { it.uri != uri }.joinToString("\n") { "${it.uri}\t${it.title}\t${it.percent}\t${it.time}" }).apply()
    }

    fun loadSettings(ctx: Context): Triple<Int, Int, Boolean> = sp(ctx).let { Triple(it.getInt("font", 18), it.getInt("theme", 0), it.getBoolean("serif", true)) }
    fun saveSettings(ctx: Context, font: Int, theme: Int, serif: Boolean) { sp(ctx).edit().putInt("font", font).putInt("theme", theme).putBoolean("serif", serif).apply() }
    fun loadWpm(ctx: Context) = sp(ctx).getInt("wpm", 300)
    fun saveWpm(ctx: Context, v: Int) { sp(ctx).edit().putInt("wpm", v).apply() }
}
