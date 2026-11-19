package com.ercan.heybe.core

/** Video dosya adından altyazı arama sorgusu: "Film.Adi.2021.1080p.WEB-DL.x264.mkv" → "Film Adi 2021". */
object SubQuery {
    private val stop = Regex("(?i)\\b(2160p|1080p|720p|480p|4k|uhd|hdr|web[- ]?dl|webrip|web|bluray|blu[- ]?ray|brrip|bdrip|dvdrip|hdtv|hdrip|x264|x265|h\\.?264|h\\.?265|hevc|aac|ac3|dts|ddp?5\\.1|atmos|10bit|proper|repack|extended|yify|rarbg|türkçe|turkce|dublaj|altyazı|altyazi)\\b.*$")

    fun clean(fileName: String): String {
        var s = fileName.substringBeforeLast('.').let { if (it.isEmpty()) fileName else it }
        s = s.replace(Regex("[._]+"), " ").replace(Regex("\\[[^]]*]|\\([^)]*(?:rip|p|x26\\d)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        val ep = Regex("(?i)\\bS\\d{1,2}E\\d{1,3}\\b").find(s)
        if (ep != null) s = s.substring(0, ep.range.last + 1)
        else {
            val year = Regex("\\b(19|20)\\d{2}\\b").find(s)
            if (year != null && year.range.first > 0) s = s.substring(0, year.range.last + 1)
        }
        s = s.replace(stop, "")
        return s.replace(Regex("[-–]+\\s*$"), "").replace(Regex("\\s+"), " ").trim()
    }
}
