package com.ercan.heybe.core

import java.math.BigInteger

/** OCR metnindeki işe yarar parçalar: IBAN, telefon, e-posta, bağlantı. */
object TextFinds {
    enum class Kind(val label: String) { IBAN("IBAN"), TELEFON("Telefon"), EPOSTA("E-posta"), LINK("Bağlantı") }
    data class Find(val kind: Kind, val value: String)

    /** TR IBAN: boşluklu yazılmış olsa da bulur, mod-97 ile doğrular. */
    fun ibans(text: String): List<String> {
        val compact = text.uppercase().replace(Regex("[ \\t]"), "")
        return Regex("TR\\d{24}").findAll(compact).map { it.value }.filter(::validIban).distinct()
            .map { it.chunked(4).joinToString(" ") }.toList()
    }

    fun validIban(iban: String): Boolean {
        val s = iban.replace(" ", "").uppercase()
        if (s.length < 15) return false
        val moved = s.substring(4) + s.substring(0, 4)
        val digits = moved.map { if (it.isLetter()) (it - 'A' + 10).toString() else it.toString() }.joinToString("")
        return BigInteger(digits).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    fun phones(text: String): List<String> =
        Regex("(?<!\\d)(?:\\+90[\\s-]?|0)?\\(?5\\d{2}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}(?!\\d)|(?<!\\d)(?:\\+90[\\s-]?|0)?\\(?[2-4]\\d{2}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}(?!\\d)")
            .findAll(text).map { m ->
                val d = m.value.filter(Char::isDigit).let { if (it.startsWith("90")) it.substring(2) else it }.removePrefix("0")
                "0${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6, 8)} ${d.substring(8)}"
            }.distinct().toList()

    fun emails(text: String): List<String> =
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(text).map { it.value.lowercase() }.distinct().toList()

    fun links(text: String): List<String> =
        Regex("(?:https?://|www\\.)[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()

    fun all(text: String): List<Find> =
        ibans(text).map { Find(Kind.IBAN, it) } + phones(text).map { Find(Kind.TELEFON, it) } +
            emails(text).map { Find(Kind.EPOSTA, it) } + links(text).map { Find(Kind.LINK, it) }
}
