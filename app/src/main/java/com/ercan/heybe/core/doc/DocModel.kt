package com.ercan.heybe.core.doc

/** Biçimden bağımsız belge modeli: dönüştürücünün ortak dili. */
/** Biçimli metin parçası. */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false)

sealed class Block {
    data class Heading(val level: Int, val text: String) : Block()
    data class Para(val text: String) : Block()
    /** Biçimli paragraf; align: 0 sol, 1 orta, 2 sağ, 3 iki yana. */
    data class Rich(val runs: List<Run>, val align: Int = 0) : Block() {
        val text: String get() = runs.joinToString("") { it.text }
    }
    data class Table(val rows: List<List<String>>) : Block()
    class Image(val jpeg: ByteArray, val width: Int, val height: Int) : Block()
}

data class Doc(val title: String, val blocks: List<Block>)

/** Bloğun düz metni (başlık, paragraf, biçimli paragraf; tablo satırları sekmeyle). */
fun Block.plain(): String? = when (this) {
    is Block.Heading -> text; is Block.Para -> text; is Block.Rich -> text
    is Block.Table -> rows.joinToString("\n") { it.joinToString("\t") }
    is Block.Image -> null
}

/** PDF'ten okunan ham satır (PDF koordinatları: üstten aşağı). */
data class Line(val text: String, val size: Float, val top: Float, val bottom: Float, val left: Float, val right: Float)

sealed class PageItem {
    data class Text(val line: Line) : PageItem()
    /** OCR'dan hazır gelen paragraf. */
    data class Ready(val text: String) : PageItem()
    class Img(val image: Block.Image) : PageItem()
}

data class Page(val items: List<PageItem>, val width: Float, val height: Float)

object Xml {
    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> if (c.code < 0x20 && c != '\n' && c != '\t' && c != '\r') {} else append(c)
        }
    }
}
