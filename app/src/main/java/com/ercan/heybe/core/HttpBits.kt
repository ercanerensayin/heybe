package com.ercan.heybe.core

import java.net.URLDecoder
import java.net.URLEncoder

/** Wi-Fi aktarım sunucusunun saf yardımcıları. */
object HttpBits {
    data class Request(val method: String, val path: String, val query: Map<String, String>)

    fun parseRequestLine(line: String): Request? {
        val p = line.trim().split(' ')
        if (p.size < 2) return null
        val target = p[1]
        val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
        val q = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(p[0].uppercase(), path, q)
    }

    /** Türkçe karakterli dosya adı için indirme başlığı (RFC 5987). */
    fun contentDisposition(name: String): String {
        val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$enc"
    }

    fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Yüklenen dosya adını güvenli hale getirir (yol bileşenleri ve yasak karakterler atılır). */
    fun safeName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").trim().trim('.').ifEmpty { "dosya" }.take(120)

    /** Arayüz adı + IPv4 listesinden en uygun adresi seçer: wlan > ap/swlan (telefon erişim noktası) > diğer özel ağlar. */
    fun pickAddress(candidates: List<Pair<String, String>>): String? {
        fun private(ip: String) = ip.startsWith("192.168.") || ip.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip)
        val ok = candidates.filter { private(it.second) }
        return (ok.firstOrNull { it.first.startsWith("wlan0") } ?: ok.firstOrNull { it.first.startsWith("wlan") }
            ?: ok.firstOrNull { it.first.startsWith("ap") || it.first.startsWith("swlan") } ?: ok.firstOrNull())?.second
    }
}
