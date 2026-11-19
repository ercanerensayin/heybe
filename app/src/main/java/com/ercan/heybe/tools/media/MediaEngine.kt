package com.ercan.heybe.tools.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.ercan.heybe.core.FileNamer
import com.ercan.heybe.core.MediaMath
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.tools.pdf.PdfEngine
import com.ercan.heybe.tools.pdf.PdfResult
import com.ercan.heybe.tools.pdf.PdfUserError
import com.ercan.heybe.tools.video.YtDlp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class MediaInfo(val durationMs: Long, val hasVideo: Boolean, val width: Int, val height: Int, val name: String, val mime: String)

enum class CompressPreset(val label: String, val shortSide: Int, val bitrate: Int) {
    WHATSAPP("WhatsApp için (480p)", 480, 1_000_000),
    ORTA("Orta (720p, önerilen)", 720, 2_500_000),
    YUKSEK("Yüksek (1080p)", 1080, 5_000_000),
}

object MediaEngine {
    fun info(ctx: Context, uri: Uri): MediaInfo {
        val name = PdfEngine.displayName(ctx, uri)
        val mime = ctx.contentResolver.getType(uri) ?: ""
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            MediaInfo(dur, hasVideo, w, h, name, mime)
        } catch (e: Exception) {
            throw PdfUserError("Dosya okunamadı: $name")
        } finally { runCatching { r.release() } }
    }

    // ---------- FFmpeg (video indiricinin getirdiği) ----------

    private suspend fun ffmpeg(ctx: Context, args: List<String>, durationMs: Long, report: (String) -> Unit) = withContext(Dispatchers.IO) {
        YtDlp.ensureReady(ctx)
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pk = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(bin.absolutePath, "-y", "-hide_banner", "-nostdin") + args).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = "${File(pk, "python/usr/lib").absolutePath}:${File(pk, "ffmpeg/usr/lib").absolutePath}"
        val p = pb.start()
        val tail = ArrayDeque<String>()
        p.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                MediaMath.ffmpegTime(line)?.let { t -> if (durationMs > 0) report("%${(t * 100 / durationMs).coerceIn(0, 100)}") }
                tail.addLast(line); if (tail.size > 8) tail.removeFirst()
            }
        }
        val code = p.waitFor()
        if (code != 0) throw Exception(tail.lastOrNull { it.isNotBlank() } ?: "FFmpeg hata kodu $code")
    }

    private fun copyToCache(ctx: Context, uri: Uri, ext: String): File {
        val dir = File(ctx.cacheDir, "medya").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "girdi.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: throw PdfUserError("Dosya açılamadı")
        return f
    }

    private suspend fun store(ctx: Context, file: File, folder: FileStore.Folder, name: String, mime: String): FileStore.Saved =
        withContext(Dispatchers.IO) {
            val s = FileStore.save(ctx, folder, name, mime) { os -> file.inputStream().use { it.copyTo(os) } }
            Recorder.done(ctx, "medya", s, mime); s
        }

    /** Videodan ses: önce MP3, olmazsa M4A (AAC). */
    suspend fun extractAudio(ctx: Context, uri: Uri, info: MediaInfo, report: (String) -> Unit): PdfResult {
        report("Dosya hazırlanıyor…")
        val ext = info.name.substringAfterLast('.', "mp4").ifBlank { "mp4" }
        val input = withContext(Dispatchers.IO) { copyToCache(ctx, uri, ext) }
        val outMp3 = File(input.parentFile, "cikti.mp3")
        val (out, mime) = try {
            report("Bileşenler hazırlanıyor…")
            ffmpeg(ctx, listOf("-i", input.absolutePath, "-vn", "-c:a", "libmp3lame", "-q:a", "2", outMp3.absolutePath), info.durationMs) { report("MP3'e çevriliyor $it") }
            outMp3 to "audio/mpeg"
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val m4a = File(input.parentFile, "cikti.m4a")
            ffmpeg(ctx, listOf("-i", input.absolutePath, "-vn", "-c:a", "aac", "-b:a", "192k", m4a.absolutePath), info.durationMs) { report("Ses çıkarılıyor $it") }
            m4a to "audio/mp4"
        }
        val saved = store(ctx, out, FileStore.Folder.SES, FileNamer.derived(info.name, "ses", out.extension), mime)
        withContext(Dispatchers.IO) { input.parentFile?.deleteRecursively() }
        return PdfResult(listOf(saved), "Ses çıkarıldı: ${saved.name} (${PdfEngine.mb(saved.size)}).", FileStore.Folder.SES, mime)
    }

    /** Kırpma: ses dosyalarında kayıpsız (FFmpeg kopya), videolarda hassas (Transformer). */
    suspend fun trim(ctx: Context, uri: Uri, info: MediaInfo, startMs: Long, endMs: Long, report: (String) -> Unit): PdfResult {
        if (endMs - startMs < 500) throw PdfUserError("Seçilen bölüm çok kısa.")
        val label = "kirpilmis"
        return if (!info.hasVideo) {
            report("Dosya hazırlanıyor…")
            val ext = info.name.substringAfterLast('.', "m4a").ifBlank { "m4a" }
            val input = withContext(Dispatchers.IO) { copyToCache(ctx, uri, ext) }
            val out = File(input.parentFile, "cikti.$ext")
            ffmpeg(ctx, listOf("-ss", MediaMath.stamp(startMs), "-i", input.absolutePath, "-t", MediaMath.stamp(endMs - startMs),
                "-c", "copy", "-map", "0:a", out.absolutePath), endMs - startMs) { report("Kırpılıyor $it") }
            val mime = info.mime.ifBlank { "audio/*" }
            val saved = store(ctx, out, FileStore.Folder.SES, FileNamer.derived(info.name, label, ext), mime)
            withContext(Dispatchers.IO) { input.parentFile?.deleteRecursively() }
            PdfResult(listOf(saved), "Kırpıldı: ${MediaMath.stamp(endMs - startMs).substringBeforeLast('.')} uzunluğunda.", FileStore.Folder.SES, mime)
        } else {
            val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
            ).build()
            val out = transform(ctx, EditedMediaItem.Builder(item).build(), null, report)
            val saved = store(ctx, out, FileStore.Folder.VIDEO, FileNamer.derived(info.name, label, "mp4"), "video/mp4")
            withContext(Dispatchers.IO) { out.delete() }
            PdfResult(listOf(saved), "Kırpıldı: ${MediaMath.stamp(endMs - startMs).substringBeforeLast('.')} uzunluğunda (${PdfEngine.mb(saved.size)}).", FileStore.Folder.VIDEO, "video/mp4")
        }
    }

    suspend fun compress(ctx: Context, uri: Uri, info: MediaInfo, preset: CompressPreset, report: (String) -> Unit): PdfResult {
        if (!info.hasVideo) throw PdfUserError("Sıkıştırma yalnızca videolar içindir.")
        val effects = mutableListOf<androidx.media3.common.Effect>()
        val short = minOf(info.width, info.height)
        if (short > 0 && MediaMath.shouldScale(short, preset.shortSide)) {
            val f = preset.shortSide.toFloat() / short
            val w = ((info.width * f) / 2).roundToInt() * 2
            val h = ((info.height * f) / 2).roundToInt() * 2
            effects += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(), effects)).build()
        val before = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        val out = transform(ctx, edited, preset.bitrate, report)
        val saved = store(ctx, out, FileStore.Folder.VIDEO, FileNamer.derived(info.name, "sikistirilmis", "mp4"), "video/mp4")
        withContext(Dispatchers.IO) { out.delete() }
        val summary = if (before > 0) "${PdfEngine.mb(before)} → ${PdfEngine.mb(saved.size)}" +
            (if (saved.size < before) " (%${100 - saved.size * 100 / before} küçüldü)." else " (küçülmedi; daha düşük bir ayar seç).")
            else "Yeni boyut ${PdfEngine.mb(saved.size)}."
        return PdfResult(listOf(saved), summary, FileStore.Folder.VIDEO, "video/mp4")
    }

    /** Media3 Transformer ile dönüştürür (ana iş parçacığında başlar), ilerlemeyi bildirir. */
    private suspend fun transform(ctx: Context, edited: EditedMediaItem, bitrate: Int?, report: (String) -> Unit): File {
        val dir = File(ctx.cacheDir, "medya").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "cikti.mp4")
        return withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val b = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            if (bitrate != null) b.setEncoderFactory(
                DefaultEncoderFactory.Builder(ctx).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build()
            )
            val t = b.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(Exception(exportException.message ?: "Dönüştürme hatası"))
                }
            }).build()
            t.start(edited, out.absolutePath)
            try {
                coroutineScope {
                    val poll = launch {
                        val h = ProgressHolder()
                        while (true) {
                            if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) report("İşleniyor %${h.progress}")
                            delay(500)
                        }
                    }
                    try { done.await() } finally { poll.cancel() }
                }
            } catch (e: CancellationException) { t.cancel(); throw e }
            out
        }
    }
}
