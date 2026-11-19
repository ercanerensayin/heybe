package com.ercan.heybe.tools.convert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ercan.heybe.R
import com.ercan.heybe.data.HeybeDb
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.tools.pdf.PdfUserError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Dönüştürme işleri arka planda sırayla yapılır; ilerleme bildirimde ve İndirilenler'de. */
class ConvertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private const val CHANNEL = "donusturme"
        private const val K_PATHS = "paths"; private const val K_NAMES = "names"; private const val K_MIMES = "mimes"
        private const val K_TARGET = "target"; private const val K_IDS = "ids"

        suspend fun enqueue(ctx: Context, files: List<Triple<File, String, String?>>, target: Target) {
            val ids = files.map { (_, name, _) -> Recorder.start(ctx, "donustur", "$name → ${target.label}", target.mime) }
            val req = OneTimeWorkRequestBuilder<ConvertWorker>().setInputData(workDataOf(
                K_PATHS to files.map { it.first.absolutePath }.toTypedArray(),
                K_NAMES to files.map { it.second }.toTypedArray(),
                K_MIMES to files.map { it.third ?: "" }.toTypedArray(),
                K_TARGET to target.name,
                K_IDS to ids.toLongArray(),
            )).build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }

    private fun notification(text: String, percent: Int?) = NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_heybe).setContentTitle("Dönüştürülüyor").setContentText(text)
        .setOnlyAlertOnce(true).setOngoing(percent != null)
        .apply { if (percent != null) setProgress(100, percent, false) }.build()

    private fun fg(text: String, percent: Int) = ForegroundInfo(4242, notification(text, percent),
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)

    override suspend fun getForegroundInfo() = fg("Hazırlanıyor…", 0)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Dönüştürme", NotificationManager.IMPORTANCE_LOW))
        val paths = inputData.getStringArray(K_PATHS) ?: return Result.failure()
        val names = inputData.getStringArray(K_NAMES) ?: return Result.failure()
        val mimes = inputData.getStringArray(K_MIMES) ?: arrayOfNulls<String>(paths.size).map { "" }.toTypedArray()
        val ids = inputData.getLongArray(K_IDS) ?: return Result.failure()
        val target = runCatching { Target.valueOf(inputData.getString(K_TARGET)!!) }.getOrNull() ?: return Result.failure()
        runCatching { setForeground(fg("Hazırlanıyor…", 0)) }

        var failed = 0
        for (i in paths.indices) {
            val file = File(paths[i]); val name = names[i]; val id = ids[i]
            val prefix = if (paths.size > 1) "(${i + 1}/${paths.size}) " else ""
            try {
                var last = -1
                val saved = withContext(Dispatchers.IO) {
                    ConvertEngine.convert(ctx, file, name, mimes[i].ifEmpty { null }, target) { p, msg ->
                        if (p != last) {
                            last = p
                            runBlocking { Recorder.progress(ctx, id, p); runCatching { setForeground(fg("$prefix$name: $msg", p)) } }
                        }
                    }
                }
                Recorder.finish(ctx, id, saved.first())
                HeybeDb.get(ctx).history().get(id)?.let { h ->
                    HeybeDb.get(ctx).history().update(h.copy(mime = target.mime, title = if (saved.size > 1) "${saved.first().name} (+${saved.size - 1} sayfa)" else h.title.substringBefore(" → ").let { saved.first().name }))
                }
                saved.drop(1).forEach { Recorder.done(ctx, "donustur", it, target.mime) }
            } catch (e: OutOfMemoryError) {
                failed++; Recorder.fail(ctx, id, "Bellek yetmedi; dosya çok büyük.")
            } catch (e: PdfUserError) {
                failed++; Recorder.fail(ctx, id, e.message ?: "Dönüştürülemedi")
            } catch (e: Exception) {
                failed++; Recorder.fail(ctx, id, "Dönüştürülemedi: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                file.delete()
            }
        }
        file(paths.firstOrNull())?.parentFile?.let { dir -> if (dir.listFiles().isNullOrEmpty()) dir.delete() }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val text = if (failed == 0) "${paths.size} dosya ${target.label} biçimine çevrildi." else "${paths.size - failed} başarılı, $failed başarısız. Ayrıntılar İndirilenler'de."
        runCatching { nm.notify(4243, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_heybe)
            .setContentTitle("Dönüştürme bitti").setContentText(text).setAutoCancel(true).build()) }
        return Result.success()
    }

    private fun file(p: String?) = p?.let(::File)
}
