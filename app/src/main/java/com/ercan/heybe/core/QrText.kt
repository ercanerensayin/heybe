package com.ercan.heybe.core

/** QR içerik biçimleri: Wi-Fi metni üretme ve okuma. */
object QrText {
    enum class Security(val code: String, val label: String) { WPA("WPA", "WPA/WPA2/WPA3"), WEP("WEP", "WEP"), NONE("nopass", "Şifresiz") }

    data class Wifi(val ssid: String, val password: String, val security: Security, val hidden: Boolean = false)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")

    fun wifi(w: Wifi): String = buildString {
        append("WIFI:T:").append(w.security.code).append(';')
        append("S:").append(esc(w.ssid)).append(';')
        if (w.security != Security.NONE) append("P:").append(esc(w.password)).append(';')
        if (w.hidden) append("H:true;")
        append(';')
    }

    fun parseWifi(raw: String): Wifi? {
        if (!raw.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = mutableMapOf<String, String>()
        val body = raw.substring(5)
        var i = 0
        while (i < body.length) {
            val colon = body.indexOf(':', i)
            if (colon < 0) break
            val key = body.substring(i, colon).uppercase()
            val sb = StringBuilder()
            var j = colon + 1
            while (j < body.length && body[j] != ';') {
                if (body[j] == '\\' && j + 1 < body.length) { sb.append(body[j + 1]); j += 2 } else { sb.append(body[j]); j++ }
            }
            if (key.isNotEmpty()) fields[key] = sb.toString()
            i = j + 1
            if (i < body.length && body[i] == ';') break
        }
        val ssid = fields["S"] ?: return null
        val sec = when (fields["T"]?.uppercase()) { "WEP" -> Security.WEP; "NOPASS", "", null -> if (fields["P"].isNullOrEmpty()) Security.NONE else Security.WPA; else -> Security.WPA }
        return Wifi(ssid, fields["P"].orEmpty(), sec, fields["H"].equals("true", true))
    }

    fun isUrl(raw: String) = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(raw.trim())
}
