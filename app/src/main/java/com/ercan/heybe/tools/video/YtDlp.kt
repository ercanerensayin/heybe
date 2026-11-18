package com.ercan.heybe.tools.video

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** yt-dlp + FFmpeg: ilk kullanımda hazırlanır, haftada bir ve hata anında güncellenir. */
object YtDlp {
    private val lock = Mutex()
    @Volatile private var ready = false
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)

    suspend fun ensureReady(ctx: Context) = lock.withLock {
        if (ready) return@withLock
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
        }
        ready = true
        val last = prefs(ctx).getLong("last_update", 0L)
        if (System.currentTimeMillis() - last > WEEK) runCatching { updateNow(ctx) }
    }

    /** yt-dlp'yi kararlı sürüme günceller; durumu Türkçe metin olarak döndürür. */
    suspend fun updateNow(ctx: Context): String = withContext(Dispatchers.IO) {
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        prefs(ctx).edit().putLong("last_update", System.currentTimeMillis()).apply()
        when (status?.name) {
            "DONE" -> "yt-dlp güncellendi"
            "ALREADY_UP_TO_DATE" -> "yt-dlp zaten güncel"
            else -> "Güncelleme durumu: ${status?.name ?: "bilinmiyor"}"
        }
    }

    fun version(ctx: Context): String? =
        if (!ready) null else runCatching { YoutubeDL.getInstance().version(ctx.applicationContext) }.getOrNull()

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }
}
