package com.ercan.heybe.core

/** Site adresi düzeltme ve varsayılan ad. */
object SiteUrl {
    /** "turkiye.gov.tr" → "https://turkiye.gov.tr"; geçersizse null. */
    fun normalize(input: String): String? {
        val t = input.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
        if (!withScheme.startsWith("http://", true) && !withScheme.startsWith("https://", true)) return null
        val host = host(withScheme) ?: return null
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        return withScheme
    }

    fun host(url: String): String? =
        Regex("^[a-zA-Z]+://([^/?#:]+)").find(url)?.groupValues?.get(1)?.lowercase()

    /** "www.turkiye.gov.tr" → "turkiye.gov.tr" */
    fun defaultName(url: String): String = host(url)?.removePrefix("www.") ?: url
}
