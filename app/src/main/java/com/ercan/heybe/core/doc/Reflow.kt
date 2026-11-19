package com.ercan.heybe.core.doc

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PDF satırlarını okunabilir akışa çevirir:
 * tekrar eden üst/alt bilgileri ve sayfa numaralarını atar, satırları paragraflara birleştirir,
 * satır sonu tirelerini düzeltir, büyük puntoları başlık yapar.
 */
object Reflow {
    private const val EDGE = 0.09f

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ")

    private val pageNumber = Regex("^(?:sayfa\\s*)?[-–—(]?\\s*\\d{1,4}\\s*[-–—)]?(?:\\s*/\\s*\\d{1,4})?$|^page\\s*\\d+", RegexOption.IGNORE_CASE)

    fun isEdge(l: Line, p: Page) = l.top < p.height * EDGE || l.bottom > p.height * (1 - EDGE)

    /** Sayfaların en az %40'ında kenarda tekrar eden satırlar (en az 3 sayfa). */
    fun repeatedEdges(pages: List<Page>): Set<String> {
        if (pages.size < 3) return emptySet()
        val counts = HashMap<String, Int>()
        for (p in pages) {
            p.items.filterIsInstance<PageItem.Text>().map { it.line }.filter { isEdge(it, p) }
                .map { norm(it.text) }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val min = maxOf(2, (pages.size * 0.4).roundToInt())
        return counts.filter { it.value >= min && it.key.isNotBlank() }.keys
    }

    /** Karakter sayısıyla ağırlıklandırılmış en yaygın punto. */
    fun bodySize(lines: List<Line>): Float {
        val w = HashMap<Int, Int>()
        lines.forEach { l -> val k = (l.size * 2).roundToInt(); w[k] = (w[k] ?: 0) + l.text.length }
        return (w.maxByOrNull { it.value }?.key ?: 22) / 2f
    }

    fun run(title: String, pages: List<Page>): Doc {
        val repeated = repeatedEdges(pages)
        val allLines = pages.flatMap { p -> p.items.filterIsInstance<PageItem.Text>().map { it.line } }
        val body = bodySize(allLines)
        val gaps = pages.flatMap { p ->
            p.items.filterIsInstance<PageItem.Text>().map { it.line }.zipWithNext { a, b -> b.top - a.bottom }.filter { it > -2 && it < body * 3 }
        }.sorted()
        val typicalGap = if (gaps.isEmpty()) body * 0.3f else gaps[gaps.size / 2]
        val widths = allLines.filter { abs(it.size - body) < 0.6f }.map { it.right - it.left }.sorted()
        val fullWidth = if (widths.isEmpty()) 0f else widths[(widths.size * 0.8).toInt().coerceAtMost(widths.lastIndex)]

        val out = mutableListOf<Block>()
        val para = StringBuilder()
        var prev: Line? = null

        fun flush() {
            val t = para.toString().replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) out += Block.Para(t)
            para.clear()
        }

        for (p in pages) {
            for (item in p.items) {
                when (item) {
                    is PageItem.Img -> { flush(); out += item.image; prev = null }
                    is PageItem.Ready -> { flush(); item.text.split(Regex("\\n\\s*\\n")).map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }.forEach { out += Block.Para(it) }; prev = null }
                    is PageItem.Text -> {
                        val l = item.line
                        val text = l.text.trim()
                        if (text.isEmpty()) continue
                        if (isEdge(l, p) && (norm(text) in repeated || pageNumber.matches(text))) continue
                        val level = when {
                            l.size >= body * 1.6f && text.length < 120 -> 1
                            l.size >= body * 1.22f && text.length < 140 -> 2
                            else -> 0
                        }
                        if (level > 0) {
                            flush()
                            val last = out.lastOrNull()
                            if (last is Block.Heading && last.level == level && prev != null && (l.top - prev!!.bottom) < body * 1.2f)
                                out[out.lastIndex] = Block.Heading(level, last.text + " " + text)
                            else out += Block.Heading(level, text)
                            prev = l
                            continue
                        }
                        val pv = prev
                        val newPara = when {
                            pv == null -> para.isEmpty()
                            l.top - pv.bottom > typicalGap * 1.8f + body * 0.25f -> true
                            fullWidth > 0 && (pv.right - pv.left) < fullWidth * 0.72f && endsSentence(para) -> true
                            else -> false
                        }
                        if (newPara) flush()
                        appendLine(para, text)
                        prev = l
                    }
                }
            }
            // Sayfa geçişinde: cümle bitmediyse paragraf sonraki sayfada sürer.
            if (endsSentence(para)) flush()
            prev = null
        }
        flush()
        return Doc(title, out)
    }

    private fun endsSentence(sb: CharSequence): Boolean {
        val t = sb.trimEnd()
        return t.isNotEmpty() && t.last() in ".!?:…\"”’)»"
    }

    /** Satırı paragrafa ekler; "keli-" + "me" → "kelime". */
    fun appendLine(sb: StringBuilder, line: String) {
        if (sb.isEmpty()) { sb.append(line); return }
        val endsHyphen = sb.length >= 2 && (sb.last() == '-' || sb.last() == '­') && sb[sb.length - 2].isLetter()
        if (endsHyphen && line.firstOrNull()?.isLowerCase() == true) { sb.setLength(sb.length - 1); sb.append(line) }
        else sb.append(' ').append(line)
    }
}
