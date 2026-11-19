package com.ercan.heybe.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.ercan.heybe.core.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases üzerinden uygulama güncellemesi: denetle, indir, kurulum ekranını aç. */
object Updater {
    private const val API = "https://api.github.com/repos/ercanerensayin/heybe/releases/tags/son-surum"
    const val RELEASES_PAGE = "https://github.com/ercanerensayin/heybe/releases/tag/son-surum"

    data class Available(val info: ReleaseInfo, val apkUrl: String, val size: Long)

    sealed class Check {
        data object UpToDate : Check()
        data class NewVersion(val available: Available) : Check()
        data class Failed(val message: String) : Check()
    }

    /** Uygulamanın her yerinden görülen "yeni sürüm var" durumu. */
    var pending by mutableStateOf<Available?>(null)

    fun installedCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Heybe")
    }

    suspend fun check(ctx: Context): Check = withContext(Dispatchers.IO) {
        try {
            val c = open(API)
            when (c.responseCode) {
                200 -> {}
                404 -> return@withContext Check.Failed("Sürüm bilgisi bulunamadı (GitHub deposu herkese açık olmalı).")
                403 -> return@withContext Check.Failed("GitHub şu an yanıt vermiyor (istek sınırı). Biraz sonra yeniden dene.")
                else -> return@withContext Check.Failed("GitHub yanıtı: ${c.responseCode}")
            }
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val info = ReleaseInfo.parse(json.optString("body")) ?: return@withContext Check.Failed("Sürüm notunda sürüm numarası yok.")
            val assets = json.optJSONArray("assets")
            var url: String? = null; var size = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size"); break }
            }
            if (url.isNullOrEmpty()) return@withContext Check.Failed("Sürümde APK dosyası bulunamadı.")
            if (info.isNewerThan(installedCode(ctx))) Check.NewVersion(Available(info, url, size)) else Check.UpToDate
        } catch (e: Exception) {
            Check.Failed("Bağlantı kurulamadı: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** APK'yı önbelleğe indirir; ilerleme 0–100. */
    suspend fun download(ctx: Context, a: Available, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "guncelleme").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Heybe-${a.info.versionName}.apk")
        var url = a.apkUrl
        var c = open(url)
        c.instanceFollowRedirects = true
        // GitHub indirmeleri farklı bir alan adına yönlendirir; elle takip edelim.
        repeat(5) {
            val code = c.responseCode
            if (code in 300..399) { url = c.getHeaderField("Location") ?: error("Yönlendirme adresi yok"); c.disconnect(); c = open(url) } else return@repeat
        }
        if (c.responseCode != 200) error("İndirme başarısız: ${c.responseCode}")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: a.size
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress(p) } }
                }
            }
        }
        file
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun openReleasesPage(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
