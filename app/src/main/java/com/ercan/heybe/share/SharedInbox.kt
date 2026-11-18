package com.ercan.heybe.share

import android.net.Uri

/** Paylaş menüsünden gelen metin ve dosyaları araç ekranı açılana kadar tutar. */
object SharedInbox {
    @Volatile private var text: String? = null
    @Volatile private var uris: List<Uri> = emptyList()

    fun put(text: String?, uris: List<Uri>) { this.text = text; this.uris = uris }
    fun takeText(): String? = text.also { text = null }
    fun takeUris(): List<Uri> = uris.also { uris = emptyList() }
}
