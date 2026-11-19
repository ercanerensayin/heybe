package com.ercan.heybe.tools.notes

import android.content.Context
import android.net.Uri
import java.io.File

/** Notlar uygulama içinde Markdown dosyası olarak durur; dış dosyalar yerinde düzenlenir. */
object NoteStore {
    data class Note(val file: File, val title: String, val preview: String, val modified: Long)

    fun dir(ctx: Context) = File(ctx.filesDir, "notlar").apply { mkdirs() }

    fun titleOf(text: String): String = text.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() }?.take(60) ?: "Adsız not"

    fun list(ctx: Context): List<Note> = (dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray())
        .map { f ->
            val t = runCatching { f.readText() }.getOrDefault("")
            val body = t.lines().dropWhile { it.isBlank() }.drop(1).joinToString(" ").trim()
            Note(f, titleOf(t), body.take(120), f.lastModified())
        }.sortedByDescending { it.modified }

    fun create(ctx: Context): File = File(dir(ctx), "not_${System.currentTimeMillis()}.md").apply { writeText("") }

    fun save(file: File, text: String) { file.writeText(text) }

    fun readUri(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Dosya açılamadı")

    fun writeUri(ctx: Context, uri: Uri, text: String) {
        (ctx.contentResolver.openOutputStream(uri, "wt") ?: error("Dosyaya yazılamadı")).use { it.write(text.toByteArray()) }
    }

    /** Dosya adı için güvenli başlık. */
    fun fileName(text: String, ext: String) = titleOf(text).replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "not" }.take(50) + "." + ext
}
