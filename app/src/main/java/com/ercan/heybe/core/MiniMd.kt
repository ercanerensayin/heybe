package com.ercan.heybe.core

/** Not defterinin önizlemesi için çok basit Markdown çözümleyici. */
object MiniMd {
    sealed class Line {
        data class H(val level: Int, val text: String) : Line()
        data class Bullet(val text: String, val done: Boolean? = null) : Line()
        data class Num(val n: String, val text: String) : Line()
        data class Quote(val text: String) : Line()
        data class P(val text: String) : Line()
        object Rule : Line()
        object Blank : Line()
    }

    fun parse(src: String): List<Line> = src.lines().map { raw ->
        val l = raw.trimEnd()
        val t = l.trimStart()
        when {
            t.isEmpty() -> Line.Blank
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> Line.Rule
            Regex("^#{1,6} ").containsMatchIn(t) -> Line.H(t.takeWhile { it == '#' }.length, t.dropWhile { it == '#' }.trim())
            Regex("^[-*+] \\[[ xX]] ").containsMatchIn(t) -> Line.Bullet(t.substring(6), t[3] != ' ')
            Regex("^[-*+] ").containsMatchIn(t) -> Line.Bullet(t.substring(2))
            Regex("^\\d+[.)] ").containsMatchIn(t) -> Line.Num(t.substringBefore(' ').dropLast(1), t.substringAfter(' '))
            t.startsWith(">") -> Line.Quote(t.removePrefix(">").trim())
            else -> Line.P(l)
        }
    }

    /** **kalın**, *italik*, `kod` parçaları: (metin, kalın, italik, kod). */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    fun spans(s: String): List<Span> {
        val out = mutableListOf<Span>()
        val re = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\*(?!\\s)(.+?)\\*|(?<![\\w])_(?!\\s)(.+?)_(?![\\w])")
        var i = 0
        for (m in re.findAll(s)) {
            if (m.range.first > i) out += Span(s.substring(i, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> Span(g[1], bold = true)
                g[2].isNotEmpty() -> Span(g[2], bold = true)
                g[3].isNotEmpty() -> Span(g[3], code = true)
                g[4].isNotEmpty() -> Span(g[4], italic = true)
                else -> Span(g[5], italic = true)
            }
            i = m.range.last + 1
        }
        if (i < s.length) out += Span(s.substring(i))
        return out
    }

    fun stats(s: String): Pair<Int, Int> = Pair(Rsvp.words(s).size, s.length)
}
