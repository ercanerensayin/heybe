package com.ercan.heybe.tools.video

/** Kalite seçenekleri ve yt-dlp argümanları (Android'den bağımsız, test edilebilir). */
enum class Quality(val label: String) {
    BEST("En yüksek"), P1080("1080p"), P720("720p"), MP3("Sadece ses (MP3)");

    /** yt-dlp seçenekleri: anahtar → değer (değersiz seçenekler için null). */
    fun args(): List<Pair<String, String?>> = when (this) {
        BEST -> listOf("-f" to "bv*+ba/b", "--merge-output-format" to "mp4/mkv")
        P1080 -> listOf("-f" to "bv*[height<=1080]+ba/b[height<=1080]/b", "--merge-output-format" to "mp4/mkv")
        P720 -> listOf("-f" to "bv*[height<=720]+ba/b[height<=720]/b", "--merge-output-format" to "mp4/mkv")
        MP3 -> listOf("-f" to "ba/b", "-x" to null, "--audio-format" to "mp3", "--audio-quality" to "0")
    }
}

object VideoOptions {
    const val SEP = "__HEYBE__"
    /** Çıktı adı şablonu: başlık + ayırıcı + yükseklik; sonradan ayrıştırılır. */
    const val TEMPLATE = "%(title).150B${SEP}%(height)s.%(ext)s"

    data class Parsed(val title: String, val quality: String?, val ext: String)

    /** "Başlık__HEYBE__1080.mp4" → (Başlık, 1080p, mp4). Yükseklik yoksa ("NA") kalite boş. */
    fun parse(fileName: String): Parsed {
        val ext = fileName.substringAfterLast('.', "")
        val base = if (ext.isEmpty()) fileName else fileName.substringBeforeLast('.')
        val i = base.lastIndexOf(SEP)
        if (i < 0) return Parsed(base, null, ext)
        val h = base.substring(i + SEP.length)
        val q = h.toIntOrNull()?.let { qualityLabel(it) }
        return Parsed(base.substring(0, i), q, ext)
    }

    fun qualityLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        else -> "${height}p"
    }

    fun mime(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** Metindeki ilk http(s) linkini bulur (paylaşılan metinler çoğu zaman başlık + link içerir). */
    fun extractUrl(text: String): String? =
        Regex("https?://[^\\s<>\"']+").find(text)?.value?.trimEnd('.', ',', ')', ']')

    /** yt-dlp hata çıktısından kullanıcıya gösterilecek kısa satır. */
    fun shortError(raw: String?): String {
        val lines = raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val err = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull() ?: "bilinmeyen hata"
        return err.removePrefix("ERROR:").trim().take(200)
    }
}
