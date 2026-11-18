package com.ercan.heybe.core

/** Dosya adı kuralları: Video_Adi_1080p.mp4, rapor_birlesik.pdf */
object FileNamer {
    private const val MAX = 80

    fun clean(raw: String): String {
        val tr = raw.replace('ı', 'i').replace('İ', 'I').replace('ş', 's').replace('Ş', 'S')
            .replace('ğ', 'g').replace('Ğ', 'G').replace('ü', 'u').replace('Ü', 'U')
            .replace('ö', 'o').replace('Ö', 'O').replace('ç', 'c').replace('Ç', 'C')
        val ascii = java.text.Normalizer.normalize(tr, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val s = ascii.replace(Regex("[^A-Za-z0-9._-]+"), "_").replace(Regex("_+"), "_").trim('_', '.', '-')
        return s.take(MAX).trimEnd('_', '.', '-').ifEmpty { "dosya" }
    }

    /** Video: başlık + çözünürlük. */
    fun video(title: String, quality: String?, ext: String): String =
        clean(title) + (quality?.let { "_" + clean(it) } ?: "") + "." + ext.trimStart('.')

    /** Diğer araçlar: orijinal ad (uzantısız) + işlem. */
    fun derived(original: String, action: String, ext: String): String {
        val base = original.substringAfterLast('/').let { if ('.' in it) it.substringBeforeLast('.') else it }
        return clean(base) + "_" + clean(action) + "." + ext.trimStart('.')
    }

    /** Eski Android'de aynı ad varsa "ad (2).uzantı" biçiminde sıradaki boş adı bulur. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (exists("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }
}
