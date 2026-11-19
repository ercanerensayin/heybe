package com.ercan.heybe.core

/** Hızlı okuma (RSVP): metni kelimelere böler, odak harfini ve her kelimenin ekranda kalma süresini hesaplar. */
object Rsvp {
    fun words(text: String): List<String> = text.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Gözün odaklandığı harf (kelimenin yaklaşık üçte biri). */
    fun focusIndex(word: String): Int {
        val letters = word.indices.filter { word[it].isLetterOrDigit() }
        if (letters.isEmpty()) return 0
        val n = letters.size
        val k = when { n <= 1 -> 0; n <= 5 -> 1; n <= 9 -> 2; n <= 13 -> 3; else -> 4 }
        return letters[k.coerceAtMost(n - 1)]
    }

    fun delayMs(word: String, wpm: Int): Long {
        val base = 60_000.0 / wpm.coerceIn(60, 1500)
        val last = word.trimEnd('"', '\'', ')', '»', '”', '’').lastOrNull()
        var f = when (last) {
            '.', '!', '?', '…' -> 2.2
            ',', ';', ':', '—', '–' -> 1.5
            else -> 1.0
        }
        if (word.length > 8) f += 0.3
        if (word.length > 13) f += 0.3
        return (base * f).toLong()
    }

    /** Kalan süre, dakika olarak (yukarı yuvarlanmış). */
    fun minutesLeft(remainingWords: Int, wpm: Int): Int = if (remainingWords <= 0) 0 else (remainingWords + wpm - 1) / wpm
}
