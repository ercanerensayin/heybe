package com.ercan.heybe.core

/** Radyo istasyonu ve favorilerin saklama biçimi (her satır: ad TAB adres TAB logo TAB kimlik). */
data class Station(val name: String, val url: String, val logo: String = "", val id: String = "", val tags: String = "", val bitrate: Int = 0)

object RadioStations {
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun encode(list: List<Station>): String = list.joinToString("\n") { listOf(clean(it.name), clean(it.url), clean(it.logo), clean(it.id)).joinToString("\t") }

    fun decode(s: String?): List<Station> = s.orEmpty().lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 2 || p[1].isBlank()) null else Station(p[0], p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
    }

    fun key(s: Station) = s.id.ifBlank { s.url }

    fun validUrl(u: String) = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE).matches(u.trim())

    /** Aynı istasyonun kopyalarını (aynı ad + aynı adres) ayıklar, boş adresleri atar. */
    fun dedupe(list: List<Station>): List<Station> {
        val seen = HashSet<String>()
        return list.filter { it.url.isNotBlank() && seen.add(it.name.trim().lowercase() + "|" + it.url.trim().lowercase()) }
    }
}
