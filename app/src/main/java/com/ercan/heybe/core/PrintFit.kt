package com.ercan.heybe.core

import kotlin.math.min

/** Görseli yazdırılabilir alana en büyük haliyle, oranı bozmadan ve ortalanmış yerleştirir. */
object PrintFit {
    data class Placement(val rotate: Boolean, val left: Float, val top: Float, val width: Float, val height: Float)

    fun place(imgW: Int, imgH: Int, boxL: Float, boxT: Float, boxW: Float, boxH: Float, autoRotate: Boolean = true): Placement {
        require(imgW > 0 && imgH > 0 && boxW > 0 && boxH > 0)
        val rotate = autoRotate && imgW != imgH && (imgW > imgH) != (boxW > boxH)
        val w = if (rotate) imgH else imgW
        val h = if (rotate) imgW else imgH
        val s = min(boxW / w, boxH / h)
        val dw = w * s
        val dh = h * s
        return Placement(rotate, boxL + (boxW - dw) / 2f, boxT + (boxH - dh) / 2f, dw, dh)
    }

    fun isPdf(mime: String?) = mime.equals("application/pdf", ignoreCase = true)
    fun isImage(mime: String?) = mime?.startsWith("image/", ignoreCase = true) == true
    fun canPrint(mime: String?) = isPdf(mime) || isImage(mime)

    /** Seçilen dosyalar birlikte yazdırılabilir mi? null = evet, değilse kullanıcıya gösterilecek neden. */
    fun problem(mimes: List<String?>): String? = when {
        mimes.isEmpty() -> "Yazdırılacak dosya seç."
        mimes.all(::isPdf) || mimes.all(::isImage) -> null
        mimes.any { !canPrint(it) } -> "Yalnızca PDF ve görseller yazdırılabilir."
        else -> "PDF'leri ve görselleri ayrı ayrı yazdır."
    }
}
