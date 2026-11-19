package com.ercan.heybe.tools.sites

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONArray
import org.json.JSONObject

data class Site(val id: Long, val name: String, val url: String)

/** Kayıtlı siteler (küçük liste; SharedPreferences içinde JSON). */
object SiteStore {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("siteler", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Site> = runCatching {
        val arr = JSONArray(sp(ctx).getString("liste", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Site(it.getLong("id"), it.getString("ad"), it.getString("url")) } }
    }.getOrDefault(emptyList())

    private fun write(ctx: Context, list: List<Site>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("ad", it.name).put("url", it.url)) }
        sp(ctx).edit().putString("liste", arr.toString()).apply()
    }

    fun upsert(ctx: Context, site: Site): List<Site> {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == site.id }
        if (i >= 0) list[i] = site else list.add(site)
        write(ctx, list); return list
    }

    fun delete(ctx: Context, id: Long): List<Site> = all(ctx).filter { it.id != id }.also { write(ctx, it) }

    fun move(ctx: Context, id: Long, delta: Int): List<Site> {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j !in list.indices) return list
        list.add(j, list.removeAt(i)); write(ctx, list); return list
    }
}

object SiteOpener {
    /** Chrome'un uygulama içi sekmesinde açar (Chrome yoksa varsayılan tarayıcıda). */
    fun open(ctx: Context, url: String) {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_ON).build()
        if (ctx !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { tab.launchUrl(ctx, Uri.parse(url)) }.onFailure {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(ctx, "Siteyi açacak tarayıcı bulunamadı.", Toast.LENGTH_LONG).show() }
        }
    }

    /** Sitenin baş harfiyle yuvarlak ikon. */
    fun letterIcon(name: String, color: Int): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE; textSize = size * 0.5f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val letter = name.trim().take(1).uppercase(java.util.Locale.forLanguageTag("tr")).ifEmpty { "?" }
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    fun pinToHome(ctx: Context, site: Site): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val intent = Intent(ctx, SiteOpenActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("url", site.url)
        val info = ShortcutInfoCompat.Builder(ctx, "site_${site.id}")
            .setShortLabel(site.name.take(20)).setLongLabel(site.name)
            .setIcon(IconCompat.createWithBitmap(letterIcon(site.name, 0xFF8E24AA.toInt())))
            .setIntent(intent).build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }
}

/** Ana ekran kısayolundan gelir: siteyi açar ve hemen kapanır. */
class SiteOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("url")?.let { SiteOpener.open(this, it) }
        finish()
    }
}
