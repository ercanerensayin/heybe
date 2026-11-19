package com.ercan.heybe.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ercan.heybe.MainActivity
import com.ercan.heybe.tools.video.YtDlp
import java.util.concurrent.TimeUnit

/** Günde bir: yeni Heybe sürümü var mı bak, gerekiyorsa yt-dlp'yi güncelle. */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private const val CHANNEL = "guncellemeler"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("gunluk_bakim", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // yt-dlp yalnızca daha önce kullanıldıysa güncellenir (ensureReady haftalık güncellemeyi kendisi yapar).
        if (ctx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE).getLong("last_update", 0L) > 0L) {
            runCatching { YtDlp.ensureReady(ctx) }
        }
        when (val r = Updater.check(ctx)) {
            is Updater.Check.NewVersion -> notifyNew(ctx, r.available.info.versionName)
            else -> {}
        }
        return Result.success()
    }

    private fun notifyNew(ctx: Context, version: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Güncellemeler", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 7,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Heybe güncellemesi var")
            .setContentText("Sürüm $version kurulmaya hazır. Dokun ve kur.")
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(7001, n) }
    }
}
