package com.ercan.heybe.core

import java.util.Locale

/** Ağ araçlarının hesapları: alt ağdaki adresler, hız, cihaz türü tahmini. */
object NetMath {
    fun ipToInt(ip: String): Int? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var v = 0
        for (s in p) { val n = s.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n }
        return v
    }

    fun intToIp(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    /** Taranacak adresler. Ağ /24'ten büyükse yalnız telefonun bulunduğu /24 taranır. */
    fun hosts(ip: String, prefix: Int): List<String> {
        val v = ipToInt(ip) ?: return emptyList()
        val pf = prefix.coerceIn(24, 30)
        val mask = if (pf == 0) 0 else (-1 shl (32 - pf))
        val net = v and mask
        val size = 1 shl (32 - pf)
        return (1 until size - 1).map { intToIp(net + it) }
    }

    fun mbps(bytes: Long, millis: Long): Double = if (millis <= 0) 0.0 else bytes * 8.0 / 1_000_000.0 / (millis / 1000.0)

    fun median(xs: List<Long>): Long {
        if (xs.isEmpty()) return 0
        val s = xs.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    fun fmtMbps(v: Double): String = if (v >= 100) String.format(Locale("tr"), "%.0f", v) else String.format(Locale("tr"), "%.1f", v)

    /** Açık portlardan tahmini cihaz türü. */
    fun guess(ports: Set<Int>, isGateway: Boolean, isSelf: Boolean): String = when {
        isSelf -> "Bu telefon"
        isGateway -> "Modem / yönlendirici"
        9100 in ports || 631 in ports || 515 in ports -> "Yazıcı"
        8008 in ports || 8009 in ports -> "TV / Chromecast"
        5555 in ports -> "Android TV / kutu"
        62078 in ports -> "iPhone / iPad"
        554 in ports -> "Kamera"
        445 in ports || 139 in ports || 3389 in ports -> "Bilgisayar"
        80 in ports || 443 in ports -> "Web arayüzlü cihaz"
        else -> "Cihaz"
    }
}
