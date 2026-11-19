package com.ercan.heybe.tools.perde

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoFile(
    val uri: Uri,
    val name: String,
    val folder: String,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val path: String?,
)

data class VideoFolder(val name: String, val videos: List<VideoFile>)

/** Telefondaki videolar (MediaStore), klasöre göre gruplu; küçük resim önbelleği. */
object PerdeLibrary {
    suspend fun folders(ctx: Context): List<VideoFolder> = withContext(Dispatchers.IO) {
        val list = mutableListOf<VideoFile>()
        val col = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DATA,
        )
        ctx.contentResolver.query(col, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                list += VideoFile(
                    uri = ContentUris.withAppendedId(col, id),
                    name = c.getString(1) ?: "video",
                    folder = c.getString(2) ?: "Diğer",
                    duration = c.getLong(3), size = c.getLong(4), dateAdded = c.getLong(5),
                    path = c.getString(6),
                )
            }
        }
        list.groupBy { it.folder }
            .map { (name, v) -> VideoFolder(name, v.sortedBy { it.name.lowercase() }) }
            .sortedByDescending { f -> f.videos.maxOf { it.dateAdded } }
    }

    fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Video"

    /** Dosyanın yolu (yalnızca MediaStore videolarında; yan altyazı dosyasını bulmak için). */
    fun pathOf(ctx: Context, uri: Uri): String? = runCatching {
        @Suppress("DEPRECATION")
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun thumbnail(ctx: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache.get(key)?.let { return@withContext it }
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY)
                ctx.contentResolver.loadThumbnail(uri, Size(320, 180), null)
            else null
        }.getOrNull() ?: runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(ctx, uri)
                    val f = getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: getFrameAtTime()
                    f?.let { b ->
                        val w = 320; val h = (b.height * 320f / b.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(b, w, h, true).also { if (it !== b) b.recycle() }
                    }
                } finally { release() }
            }
        }.getOrNull()
        bmp?.also { cache.put(key, it) }
    }
}
