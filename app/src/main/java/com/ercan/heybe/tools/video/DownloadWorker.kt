package com.ercan.heybe.tools.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ercan.heybe.core.FileNamer
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.HeybeDb
import com.ercan.heybe.data.Recorder
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val CHANNEL = "indirmeler"
        private const val K_ID = "id"
        private const val K_URL = "url"
        private const val K_Q = "q"

        fun workName(historyId: Long) = "video_$historyId"

        fun enqueue(ctx: Context, historyId: Long, url: String, quality: Quality) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(K_ID to historyId, K_URL to url, K_Q to quality.name))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(workName(historyId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context, historyId: Long) {
            YtDlp.cancel(workName(historyId))
            WorkManager.getInstance(ctx).cancelUniqueWork(workName(historyId))
        }

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private val historyId get() = inputData.getLong(K_ID, -1)
    private val notifId get() = (historyId % Int.MAX_VALUE).toInt() + 1000

    private fun notification(title: String, percent: Int?, text: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(com.ercan.heybe.R.drawable.ic_stat_heybe)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(percent != null)
            .apply { if (percent != null) setProgress(100, percent, percent <= 0) }
            .build()

    private fun foreground(title: String, percent: Int, text: String) = ForegroundInfo(
        notifId, notification(title, percent, text),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Video indiriliyor", 0, "Hazırlanıyor…")

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = historyId
        val url = inputData.getString(K_URL) ?: return Result.failure()
        val quality = runCatching { Quality.valueOf(inputData.getString(K_Q) ?: "") }.getOrDefault(Quality.BEST)
        val tmp = File(ctx.cacheDir, "video/$id").apply { deleteRecursively(); mkdirs() }
        createChannel(ctx)
        runCatching { setForeground(foreground("Video indiriliyor", 0, "Bileşenler hazırlanıyor…")) }

        return try {
            YtDlp.ensureReady(ctx)
            try {
                download(url, quality, tmp)
            } catch (e: CancellationException) {
                throw e
            } catch (first: Exception) {
                // Kendi kendine onarım: yt-dlp'yi güncelle ve bir kez daha dene.
                runCatching { setForeground(foreground("Video indiriliyor", 0, "yt-dlp güncelleniyor, yeniden deneniyor…")) }
                runCatching { YtDlp.updateNow(ctx) }
                tmp.deleteRecursively(); tmp.mkdirs()
                download(url, quality, tmp)
            }
            val out = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() } ?: error("İndirilen dosya bulunamadı")
            val parsed = VideoOptions.parse(out.name)
            val mime = VideoOptions.mime(parsed.ext)
            val name = FileNamer.video(parsed.title, if (quality == Quality.MP3) null else parsed.quality, parsed.ext)
            val saved = withContext(Dispatchers.IO) {
                FileStore.save(ctx, FileStore.Folder.VIDEO, name, mime) { os -> out.inputStream().use { it.copyTo(os) } }
            }
            HeybeDb.get(ctx).history().get(id)?.let { HeybeDb.get(ctx).history().update(it.copy(mime = mime)) }
            Recorder.finish(ctx, id, saved)
            notify(saved.name, "İndirme tamamlandı")
            Result.success()
        } catch (e: CancellationException) {
            YtDlp.cancel(workName(id))
            withContext(kotlinx.coroutines.NonCancellable) { Recorder.fail(ctx, id, "İptal edildi") }
            throw e
        } catch (e: Exception) {
            val msg = VideoOptions.shortError(e.message)
            Recorder.fail(ctx, id, msg)
            notify("İndirme başarısız", msg)
            Result.failure()
        } finally {
            tmp.deleteRecursively()
        }
    }

    private suspend fun download(url: String, quality: Quality, dir: File) {
        val ctx = applicationContext
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("-o", File(dir, VideoOptions.TEMPLATE).absolutePath)
        quality.args().forEach { (k, v) -> if (v == null) req.addOption(k) else req.addOption(k, v) }
        var lastPercent = -1
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().execute(req, processId = workName(historyId), callback = { progress: Float, eta: Long, _: String ->
                val p = progress.toInt().coerceIn(0, 100)
                if (p != lastPercent) {
                    lastPercent = p
                    runBlocking {
                        Recorder.progress(ctx, historyId, p)
                        runCatching {
                            setForeground(foreground("Video indiriliyor", p, if (eta > 0) "%$p · kalan ${eta} sn" else "%$p"))
                        }
                    }
                }
            })
        }
    }

    private fun notify(title: String, text: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(notifId + 1, notification(title, null, text)) }
    }
}
