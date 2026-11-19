package com.ercan.heybe.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.ercan.heybe.core.FileNamer
import java.io.File
import java.io.OutputStream

/** Download/Heybe/<klasör> altına kayıt. Kaynak dosyalara asla dokunulmaz; her sonuç yeni dosyadır. */
object FileStore {
    const val ROOT = "Heybe"

    enum class Folder(val dir: String) { VIDEO("Video"), PDF("PDF"), GORSEL("Görsel"), METIN("Metin"), SES("Ses"), BELGE("Belgeler"), YEDEK("Yedek"), GELEN("Gelenler"), DENEME("Deneme") }

    data class Saved(val uri: Uri, val path: String?, val name: String, val size: Long)

    /** Android 9 ve öncesinde depolama izni gerekir. */
    fun needsPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    // ----- Kullanıcının seçtiği kayıt klasörü (SAF ağacı) -----
    private const val PREF = "kayit"
    @Volatile var customLabel: String? = null
        private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun tree(ctx: Context): Uri? = sp(ctx).getString("agac", null)?.let(Uri::parse)
    fun loadLabel(ctx: Context) { customLabel = if (tree(ctx) != null) sp(ctx).getString("etiket", null) else null }
    fun setTree(ctx: Context, uri: Uri?) {
        if (uri == null) { sp(ctx).edit().remove("agac").remove("etiket").apply(); customLabel = null; return }
        val label = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)?.name ?: "Seçilen klasör"
        sp(ctx).edit().putString("agac", uri.toString()).putString("etiket", label).apply(); customLabel = label
    }

    fun locationLabel(folder: Folder? = null): String =
        (customLabel ?: "Download/$ROOT") + (folder?.let { "/" + it.dir } ?: "")

    fun save(ctx: Context, folder: Folder, name: String, mime: String, write: (OutputStream) -> Unit): Saved {
        tree(ctx)?.let { return saveTree(ctx, it, folder, name, mime, write) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(ctx, folder, name, mime, write)
        else saveLegacy(ctx, folder, name, mime, write)
    }

    private fun saveTree(ctx: Context, tree: Uri, folder: Folder, name: String, mime: String, write: (OutputStream) -> Unit): Saved {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree)
        if (root == null || !root.canWrite()) error("Seçilen kayıt klasörüne yazılamıyor; Ayarlar'dan klasörü yeniden seç ya da varsayılana dön.")
        val dir = root.findFile(folder.dir)?.takeIf { it.isDirectory } ?: root.createDirectory(folder.dir) ?: error("Klasör oluşturulamadı: ${folder.dir}")
        val unique = FileNamer.unique(name) { dir.findFile(it) != null }
        val f = dir.createFile(mime, unique) ?: error("Dosya oluşturulamadı: $unique")
        try { ctx.contentResolver.openOutputStream(f.uri)!!.use(write) } catch (e: Exception) { f.delete(); throw e }
        return Saved(f.uri, null, f.name ?: unique, f.length())
    }

    private fun isDocUri(ctx: Context, u: Uri) = android.provider.DocumentsContract.isDocumentUri(ctx, u)

    private fun saveQ(ctx: Context, folder: Folder, name: String, mime: String, write: (OutputStream) -> Unit): Saved {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$ROOT/${folder.dir}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Dosya oluşturulamadı: $name")
        try {
            cr.openOutputStream(uri)!!.use(write)
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        var finalName = name
        var size = 0L
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { finalName = c.getString(0) ?: name; size = c.getLong(1) }
        }
        return Saved(uri, null, finalName, size)
    }

    private fun saveLegacy(ctx: Context, folder: Folder, name: String, mime: String, write: (OutputStream) -> Unit): Saved {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$ROOT/${folder.dir}")
        dir.mkdirs()
        val file = File(dir, FileNamer.unique(name) { File(dir, it).exists() })
        try {
            file.outputStream().use(write)
        } catch (e: Exception) {
            file.delete(); throw e
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
        return Saved(providerUri(ctx, file), file.absolutePath, file.name, file.length())
    }

    private fun providerUri(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)

    fun exists(ctx: Context, item: HistoryItem): Boolean {
        item.path?.let { return File(it).exists() }
        val uri = item.uri?.let(Uri::parse) ?: return false
        if (isDocUri(ctx, uri)) return runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.exists() == true }.getOrDefault(false)
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    fun delete(ctx: Context, item: HistoryItem): Boolean {
        item.path?.let { return File(it).delete() }
        val uri = item.uri?.let(Uri::parse) ?: return false
        if (isDocUri(ctx, uri)) return runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.delete() == true }.getOrDefault(false)
        return runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    fun openIntent(item: HistoryItem): Intent? {
        val uri = item.uri?.let(Uri::parse) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(item: HistoryItem): Intent? {
        val uri = item.uri?.let(Uri::parse) ?: return null
        val send = Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
