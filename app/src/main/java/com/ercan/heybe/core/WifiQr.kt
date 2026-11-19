package com.ercan.heybe.core

/** Wi-Fi ağına QR kodla bağlanma metni (Android ve iPhone kameraları okur). */
object WifiQr {
    enum class Security(val code: String, val label: String) { WPA("WPA", "WPA/WPA2/WPA3"), WEP("WEP", "WEP"), NONE("nopass", "Şifresiz") }

    fun esc(s: String) = buildString { for (c in s) { if (c in "\\;,:\"") append('\\'); append(c) } }

    fun build(ssid: String, password: String, security: Security, hidden: Boolean = false): String = buildString {
        append("WIFI:T:").append(security.code).append(';')
        append("S:").append(esc(ssid)).append(';')
        if (security != Security.NONE) append("P:").append(esc(password)).append(';')
        if (hidden) append("H:true;")
        append(';')
    }

    fun problem(ssid: String, password: String, security: Security): String? = when {
        ssid.isBlank() -> "Ağ adını yaz."
        security == Security.WPA && password.length !in 8..63 -> "WPA şifresi 8–63 karakter olmalı."
        security == Security.WEP && password.isEmpty() -> "Şifreyi yaz."
        else -> null
    }

    /** Android "\"Ev\"" biçiminde verir; tırnakları ve bilinmeyen değeri temizler. */
    fun cleanSsid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        return if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") null else s
    }
}
