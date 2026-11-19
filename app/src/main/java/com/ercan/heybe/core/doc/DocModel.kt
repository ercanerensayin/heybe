package com.ercan.heybe.core.doc

/** Biçimden bağımsız belge modeli: dönüştürücünün ortak dili. */
sealed class Block {
    data class Heading(val level: Int, val text: String) : Block()
    data class Para(val text: String) : Block()
    class Image(val jpeg: ByteArray, val width: Int, val height: Int) : Block()
}

data class Doc(val title: String, val blocks: List<Block>)

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
