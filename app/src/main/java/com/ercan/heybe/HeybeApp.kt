package com.ercan.heybe

import android.app.Application
import java.io.File

class HeybeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.ercan.heybe.data.FileStore.loadLabel(this)
        com.ercan.heybe.tools.video.DownloadWorker.createChannel(this)
        runCatching { com.ercan.heybe.tools.pdf.PdfEngine.init(this) }
        runCatching { com.ercan.heybe.update.DailyWorker.schedule(this) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile().writeText(e.stackTraceToString()) }
            previous?.uncaughtException(t, e)
        }
    }

    fun crashFile(): File = File(filesDir, "son_hata.txt")
}
