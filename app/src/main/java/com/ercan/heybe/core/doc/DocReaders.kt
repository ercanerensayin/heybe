package com.ercan.heybe.core.doc

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** DOCX ve EPUB dosyalarını ortak belge modeline okur (resimleri ham bayt olarak). */
object DocReaders {
    /** Görselin boyutunu okuyamayan ortamlar için dışarıdan verilir (Android'de BitmapFactory, testte sabit). */
    class ImageCodec(val toJpeg: (ByteArray) -> Block.Image?)

    private fun parse(xml: String): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.allByLocal(name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) { e.children().forEach { if (it.localName == name) out += it; walk(it) } }
        walk(this); return out
    }

    // ---------------- DOCX ----------------

    fun docx(file: File, title: String, codec: ImageCodec): Doc = ZipFile(file).use { z ->
        val xml = z.getInputStream(z.getEntry("word/document.xml") ?: error("Geçerli bir Word dosyası değil")).readBytes().toString(Charsets.UTF_8)
        val rels = z.getEntry("word/_rels/document.xml.rels")?.let { e ->
            val d = parse(z.getInputStream(e).readBytes().toString(Charsets.UTF_8))
            d.documentElement.allByLocal("Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }
        } ?: emptyMap()
        val body = parse(xml).documentElement.allByLocal("body").firstOrNull() ?: return@use Doc(title, emptyList())
        val blocks = mutableListOf<Block>()

        fun on(rPr: Element?, name: String): Boolean {
            val e = rPr?.children()?.firstOrNull { it.localName == name } ?: return false
            val v = e.getAttributeNS(W, "val"); return v.isEmpty() || v !in setOf("0", "false", "none")
        }

        fun paragraph(p: Element) {
            val style = p.allByLocal("pStyle").firstOrNull()?.getAttributeNS(W, "val").orEmpty()
            val jc = p.children().firstOrNull { it.localName == "pPr" }?.children()?.firstOrNull { it.localName == "jc" }?.getAttributeNS(W, "val").orEmpty()
            val align = when (jc) { "center" -> 1; "right", "end" -> 2; "both", "distribute" -> 3; else -> 0 }
            val runs = mutableListOf<Run>()
            fun flushText() {
                val t = runs.joinToString("") { it.text }.replace(Regex("[ \\t]+"), " ").trim()
                if (t.isEmpty()) { runs.clear(); return }
                val lvl = Regex("(?i)^(heading|başlık|baslik)\\s*(\\d)").find(style)?.groupValues?.get(2)?.toIntOrNull()
                    ?: if (style.equals("Title", true)) 1 else 0
                blocks += when {
                    lvl > 0 -> Block.Heading(lvl.coerceAtMost(3), t)
                    runs.any { it.bold || it.italic || it.underline } || align != 0 -> Block.Rich(mergeRuns(runs), align)
                    else -> Block.Para(t)
                }
                runs.clear()
            }
            fun walkRun(r: Element) {
                val rPr = r.children().firstOrNull { it.localName == "rPr" }
                val b = on(rPr, "b"); val i = on(rPr, "i"); val u = on(rPr, "u")
                for (c in r.children()) when (c.localName) {
                    "t" -> runs += Run(c.textContent, b, i, u)
                    "tab" -> runs += Run("\t", b, i, u)
                    "br", "cr" -> runs += Run(" ", b, i, u)
                    "drawing", "pict" -> c.allByLocal("blip").forEach { bl ->
                        rels[bl.getAttributeNS(R, "embed")]?.let { target ->
                            val path = "word/" + target.removePrefix("/word/").removePrefix("./")
                            z.getEntry(path)?.let { en -> codec.toJpeg(z.getInputStream(en).readBytes())?.let { flushText(); blocks += it } }
                        }
                    }
                }
            }
            fun walk(e: Element) { for (c in e.children()) when (c.localName) { "r" -> walkRun(c); "pPr" -> {}; else -> walk(c) } }
            walk(p)
            flushText()
        }

        for (e in body.children()) when (e.localName) {
            "p" -> paragraph(e)
            "tbl" -> {
                val rows = e.allByLocal("tr").map { tr -> tr.children().filter { it.localName == "tc" }.map { tc -> tc.allByLocal("t").joinToString("") { it.textContent }.trim() } }
                    .filter { r -> r.any { it.isNotEmpty() } }
                if (rows.isNotEmpty()) blocks += Block.Table(rows)
            }
            "sdt" -> e.allByLocal("p").forEach(::paragraph)
        }
        Doc(title, blocks)
    }

    // ---------------- EPUB ----------------

    private val entities = mapOf("&nbsp;" to "&#160;", "&mdash;" to "&#8212;", "&ndash;" to "&#8211;", "&hellip;" to "&#8230;",
        "&laquo;" to "&#171;", "&raquo;" to "&#187;", "&ldquo;" to "&#8220;", "&rdquo;" to "&#8221;", "&lsquo;" to "&#8216;",
        "&rsquo;" to "&#8217;", "&copy;" to "&#169;", "&shy;" to "")

    fun cleanXhtml(s: String): String {
        var t = s.replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
        entities.forEach { (k, v) -> t = t.replace(k, v) }
        return t
    }

    fun epub(file: File, fallbackTitle: String, codec: ImageCodec): Doc = ZipFile(file).use { z ->
        fun read(path: String) = z.getEntry(path)?.let { z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
        val container = parse(read("META-INF/container.xml") ?: error("Geçerli bir EPUB değil"))
        val opfPath = container.documentElement.allByLocal("rootfile").first().getAttribute("full-path")
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opf = parse(read(opfPath) ?: error("OPF bulunamadı")).documentElement
        val title = opf.allByLocal("title").firstOrNull()?.textContent?.trim()?.ifEmpty { null } ?: fallbackTitle
        val manifest = opf.allByLocal("item").associate { it.getAttribute("id") to it.getAttribute("href") }
        val spine = opf.allByLocal("itemref").mapNotNull { manifest[it.getAttribute("idref")] }
        val blocks = mutableListOf<Block>()

        for (href in spine) {
            val path = resolve(base, href.substringBefore('#'))
            val raw = read(path) ?: continue
            val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val doc = runCatching { parse(cleanXhtml(raw)) }.getOrNull()
            if (doc == null) { // bozuk XHTML: etiketleri silip paragrafla
                raw.replace(Regex("<[^>]+>"), "\n").split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotEmpty() }.forEach { blocks += Block.Para(it) }
                continue
            }
            val bodyEl = doc.documentElement.allByLocal("body").firstOrNull() ?: continue
            fun walk(e: Element) {
                when (e.localName?.lowercase()) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val t = e.textContent.replace(Regex("\\s+"), " ").trim()
                        if (t.isNotEmpty()) blocks += Block.Heading(e.localName.substring(1).toInt().coerceAtMost(3), t); return
                    }
                    "img", "image" -> {
                        val src = e.getAttribute("src").ifEmpty { e.getAttributeNS("http://www.w3.org/1999/xlink", "href") }
                        if (src.isNotEmpty()) z.getEntry(resolve(dir, src))?.let { en -> codec.toJpeg(z.getInputStream(en).readBytes())?.let { blocks += it } }
                        return
                    }
                    "p", "li", "blockquote", "pre", "dd", "dt", "td" -> {
                        if (e.allByLocal("p").isEmpty() && e.allByLocal("div").isEmpty()) {
                            val t = e.textContent.replace(Regex("\\s+"), " ").trim()
                            if (t.isNotEmpty()) blocks += Block.Para(if (e.localName == "li") "• $t" else t)
                            e.allByLocal("img").forEach { walk(it) }
                            return
                        }
                    }
                    "script", "style" -> return
                }
                // Doğrudan metin taşıyan div'ler
                val direct = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filter { it.nodeType == Node.TEXT_NODE }
                    .joinToString(" ") { it.textContent }.replace(Regex("\\s+"), " ").trim()
                if (direct.isNotEmpty() && e.localName != "body") blocks += Block.Para(direct)
                e.children().forEach(::walk)
            }
            walk(bodyEl)
        }
        Doc(title, blocks)
    }

    /** Aynı biçimdeki ardışık parçaları birleştirir, baştaki/sondaki boşlukları kırpar. */
    fun mergeRuns(rs: List<Run>): List<Run> {
        val out = mutableListOf<Run>()
        for (r in rs) {
            val t = r.text.replace(Regex(" {2,}"), " ")
            val last = out.lastOrNull()
            if (last != null && last.bold == r.bold && last.italic == r.italic && last.underline == r.underline) out[out.lastIndex] = last.copy(text = last.text + t)
            else out += r.copy(text = t)
        }
        if (out.isNotEmpty()) { out[0] = out[0].copy(text = out[0].text.trimStart(' ')); out[out.lastIndex] = out.last().copy(text = out.last().text.trimEnd(' ', '\t')) }
        return out.filter { it.text.isNotEmpty() }
    }

    fun resolve(base: String, href: String): String {
        val parts = (base + java.net.URLDecoder.decode(href, "UTF-8")).split('/').fold(mutableListOf<String>()) { acc, p ->
            when (p) { ".." -> if (acc.isNotEmpty()) acc.removeAt(acc.lastIndex); ".", "" -> {}; else -> acc += p }; acc
        }
        return parts.joinToString("/")
    }

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
}
