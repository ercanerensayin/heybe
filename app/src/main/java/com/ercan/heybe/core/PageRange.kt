package com.ercan.heybe.core

/** "1-3, 5, 8-" gibi sayfa aralıklarını 1 tabanlı sayfa listesine çevirir. */
object PageRange {
    class Error(message: String) : Exception(message)

    fun parse(input: String, pageCount: Int): List<Int> {
        val text = input.replace(" ", "").replace('–', '-')
        if (text.isEmpty()) throw Error("Sayfa aralığı boş.")
        val pages = LinkedHashSet<Int>()
        for (part in text.split(',', ';').filter { it.isNotEmpty() }) {
            val m = Regex("^(\\d*)-(\\d*)$").find(part)
            if (m != null) {
                val a = m.groupValues[1].toIntOrNull() ?: 1
                val b = m.groupValues[2].toIntOrNull() ?: pageCount
                if (a < 1 || b > pageCount || a > b) throw Error("Geçersiz aralık: $part (belge $pageCount sayfa)")
                (a..b).forEach { pages.add(it) }
            } else {
                val n = part.toIntOrNull() ?: throw Error("Anlaşılamadı: $part")
                if (n < 1 || n > pageCount) throw Error("Sayfa $n yok (belge $pageCount sayfa)")
                pages.add(n)
            }
        }
        return pages.toList()
    }

    /** Dosya adına eklenecek kısa etiket: [1,2,3,5] → "s1-3_5". */
    fun label(pages: List<Int>): String {
        if (pages.isEmpty()) return "s"
        val parts = mutableListOf<String>()
        var start = pages[0]; var prev = pages[0]
        for (p in pages.drop(1) + Int.MIN_VALUE) {
            if (p == prev + 1) { prev = p; continue }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = p; prev = p
        }
        return "s" + parts.joinToString("_")
    }
}
