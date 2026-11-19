package com.ercan.heybe.core.doc

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * UYAP Doküman Formatı (.udf): içinde content.xml olan ZIP.
 * Bütün metin tek bir CDATA havuzunda durur; paragraf ve biçim elemanları bu havuza
 * startOffset/length ile bağlanır (format_id 1.8).
 */
object Udf {
    data class Info(val signed: Boolean, val warnings: List<String>)

    private const val OBJ = '￼'
    private const val ZWSP = '​'

    private fun Element.kids(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.attr(name: String): String {
        if (hasAttribute(name)) return getAttribute(name)
        for (i in 0 until attributes.length) { val a = attributes.item(i); if (a.nodeName.equals(name, true)) return a.nodeValue }
        return ""
    }
    private fun Element.flag(name: String) = attr(name).equals("true", true)

    // ---------------- Okuma ----------------

    fun read(file: File, fallbackTitle: String, codec: DocReaders.ImageCodec): Pair<Doc, Info> = ZipFile(file).use { z ->
        val entry = z.getEntry("content.xml") ?: z.entries().asSequence().firstOrNull { it.name.endsWith("content.xml") }
            ?: error("Geçerli bir UDF dosyası değil (content.xml yok)")
        val xml = z.getInputStream(entry).readBytes()
        val signed = z.getEntry("sign.sgn") != null
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isCoalescing = true }
        val root = f.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        val pool = root.kids().firstOrNull { it.tagName == "content" }?.textContent ?: ""
        val warnings = mutableListOf<String>()
        val blocks = mutableListOf<Block>()
        val listCounters = HashMap<String, Int>()

        fun slice(e: Element): String {
            val s = e.attr("startOffset").toIntOrNull() ?: return ""
            val l = e.attr("length").toIntOrNull() ?: return ""
            if (s < 0 || s >= pool.length) return ""
            return pool.substring(s, minOf(pool.length, s + l))
        }
        fun clean(t: String) = t.replace("\n", "").replace(ZWSP.toString(), "").replace(OBJ.toString(), "").replace('\r', ' ')

        fun image(e: Element): Block.Image? {
            val data = e.attr("imageData").ifEmpty { return null }
            val bytes = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull() ?: return null
            return codec.toJpeg(bytes)
        }

        fun paragraph(p: Element, out: MutableList<Block>) {
            val runs = mutableListOf<Run>()
            fun flush() {
                val merged = DocReaders.mergeRuns(runs); runs.clear()
                if (merged.isEmpty()) return
                val align = p.attr("Alignment").toIntOrNull() ?: 0
                var prefix = ""
                if (p.flag("Bulleted")) prefix = "• "
                else if (p.flag("Numbered")) {
                    val key = p.attr("ListId") + ":" + p.attr("ListLevel")
                    val n = (listCounters[key] ?: 0) + 1; listCounters[key] = n
                    prefix = when {
                        p.attr("NumberType").contains("CHAR_SMALL") -> "${'a' + (n - 1) % 26}. "
                        p.attr("NumberType").contains("CHAR_BIG") -> "${'A' + (n - 1) % 26}. "
                        else -> "$n. "
                    }
                }
                val rs = if (prefix.isEmpty()) merged else listOf(Run(prefix)) + merged
                out += if (rs.any { it.bold || it.italic || it.underline } || align != 0) Block.Rich(rs, align) else Block.Para(rs.joinToString("") { it.text })
            }
            for (c in p.kids()) when (c.tagName) {
                "content", "space", "field" -> clean(slice(c)).let { t -> if (t.isNotEmpty()) runs += Run(t, c.flag("bold"), c.flag("italic"), c.flag("underline")) }
                "tab" -> runs += Run("\t")
                "image" -> { flush(); image(c)?.let { out += it } ?: warnings.add("Bir resim okunamadı.") }
                else -> clean(slice(c)).let { if (it.isNotEmpty()) runs += Run(it) }
            }
            flush()
        }

        fun cellText(cell: Element): String {
            val tmp = mutableListOf<Block>()
            cell.kids().forEach { if (it.tagName == "paragraph") paragraph(it, tmp) }
            return tmp.mapNotNull { it.plain() }.joinToString(" ").trim()
        }

        fun walk(e: Element, out: MutableList<Block>) {
            for (c in e.kids()) when (c.tagName) {
                "paragraph" -> paragraph(c, out)
                "table" -> {
                    val rows = c.kids().filter { it.tagName == "row" }.map { r -> r.kids().filter { it.tagName == "cell" }.map(::cellText) }
                        .filter { r -> r.any { it.isNotEmpty() } }
                    if (rows.isNotEmpty()) out += Block.Table(rows)
                }
                "image" -> image(c)?.let { out += it }
                "header", "footer", "page-break", "elements" -> walk(c, out)
                else -> walk(c, out)
            }
        }
        val elements = root.kids().firstOrNull { it.tagName == "elements" } ?: root
        walk(elements, blocks)
        if (signed) warnings += "Belge e-imzalı. Dönüştürülen dosyada imza bulunmaz."
        Doc(fallbackTitle, blocks) to Info(signed, warnings)
    }

    // ---------------- Yazma ----------------

    private fun cdata(s: String) = s.replace("]]>", "]]]]><![CDATA[>")
    private fun attrEsc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** Belge modelini UYAP Doküman Editörü'nün açabileceği .udf olarak yazar (Times New Roman 12). */
    fun write(doc: Doc, out: OutputStream) {
        val pool = StringBuilder()
        val el = StringBuilder()
        val font = "family=\"Times New Roman\" size=\"12\""

        fun runEl(text: String, bold: Boolean, italic: Boolean, underline: Boolean, size: Int = 12) {
            if (text.isEmpty()) return
            // Sekmeleri ayrı <tab> olarak yaz
            val parts = text.split('\t')
            parts.forEachIndexed { i, part ->
                if (part.isNotEmpty()) {
                    el.append("<content startOffset=\"${pool.length}\" length=\"${part.length}\" family=\"Times New Roman\" size=\"$size\"")
                    if (bold) el.append(" bold=\"true\""); if (italic) el.append(" italic=\"true\""); if (underline) el.append(" underline=\"true\"")
                    el.append(" />"); pool.append(part)
                }
                if (i < parts.lastIndex) { el.append("<tab startOffset=\"${pool.length}\" length=\"1\" $font />"); pool.append('\t') }
            }
        }
        /** Paragrafın satır sonu: kendi aralığında, son elemana eklenir. */
        fun newline(size: Int = 12) { el.append("<content startOffset=\"${pool.length}\" length=\"1\" family=\"Times New Roman\" size=\"$size\" />"); pool.append('\n') }
        fun para(align: Int, body: () -> Unit, size: Int = 12, firstIndent: Boolean = false) {
            el.append("<paragraph Alignment=\"$align\" LeftIndent=\"0.0\" RightIndent=\"0.0\"")
            if (firstIndent) el.append(" FirstLineIndent=\"35.4\"")
            el.append(">"); body(); newline(size); el.append("</paragraph>\n")
        }

        for (b in doc.blocks) when (b) {
            is Block.Heading -> para(if (b.level == 1) 1 else 0, { runEl(b.text, true, false, false, if (b.level == 1) 14 else 12) }, if (b.level == 1) 14 else 12)
            is Block.Para -> para(3, { runEl(b.text, false, false, false) }, firstIndent = true)
            is Block.Rich -> para(b.align, { b.runs.forEach { runEl(it.text, it.bold, it.italic, it.underline) } })
            is Block.Table -> {
                val cols = b.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
                val w = "%.1f".format(java.util.Locale.ROOT, 520.0 / cols)
                el.append("<table tableName=\"Sabit\" columnCount=\"$cols\" columnSpans=\"${List(cols) { w }.joinToString(",")}\" border=\"borderCell\">")
                b.rows.forEachIndexed { ri, row ->
                    el.append("<row rowName=\"row${ri + 1}\" rowType=\"dataRow\">")
                    for (ci in 0 until cols) {
                        el.append("<cell><paragraph Alignment=\"0\" LeftIndent=\"0.0\" RightIndent=\"0.0\">")
                        val t = row.getOrElse(ci) { "" }
                        if (t.isNotEmpty()) runEl(t, false, false, false, 10)
                        newline(10)
                        el.append("</paragraph></cell>")
                    }
                    el.append("</row>")
                }
                el.append("</table>\n")
            }
            is Block.Image -> {
                val wPt = minOf(450.0, b.width * 0.75); val hPt = if (b.width > 0) wPt * b.height / b.width else wPt
                el.append("<paragraph Alignment=\"1\" LeftIndent=\"0.0\" RightIndent=\"0.0\">")
                el.append("<image imageData=\"${Base64.getEncoder().encodeToString(b.jpeg)}\" startOffset=\"${pool.length}\" length=\"1\" width=\"${"%.1f".format(java.util.Locale.ROOT, wPt)}\" height=\"${"%.1f".format(java.util.Locale.ROOT, hPt)}\" />")
                pool.append(OBJ); newline(); el.append("</paragraph>\n")
            }
        }
        if (pool.isEmpty()) para(0, { pool.append(ZWSP); el.append("<content startOffset=\"0\" length=\"1\" $font />") })
        pool.append('\n') // son satır sonu hiçbir elemana ait değildir

        val xml = """<?xml version="1.0" encoding="UTF-8" ?>
<template format_id="1.8">
<content><![CDATA[${cdata(pool.toString())}]]></content>
<properties><pageFormat mediaSizeName="1" leftMargin="70.86614227294922" rightMargin="56.69291305541992" topMargin="56.69291305541992" bottomMargin="56.69291305541992" paperOrientation="1" headerFOffset="20.0" footerFOffset="20.0" /></properties>
<elements resolver="hvl-default">
$el</elements>
<styles><style name="default" description="Geçerli" family="Dialog" size="12" bold="false" italic="false" foreground="-13421773" FONT_ATTRIBUTE_KEY="javax.swing.plaf.FontUIResource[family=Dialog,name=Dialog,style=plain,size=12]" /><style name="hvl-default" family="Times New Roman" size="12" description="Gövde" /></styles>
</template>"""
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry("content.xml")); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        zip.finish()
    }

    /** Görüntüleyici için güvenli HTML (biçim, hizalama, tablolar, resimler). */
    fun toHtml(doc: Doc): String {
        val sb = StringBuilder("""<!DOCTYPE html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:'Times New Roman',serif;font-size:17px;line-height:1.5;margin:16px;color:#111;background:#fff}
p{margin:0 0 .5em}h1{font-size:1.3em;text-align:center}h2{font-size:1.1em}table{border-collapse:collapse;width:100%;margin:.6em 0}
td{border:1px solid #777;padding:4px 6px;font-size:.92em;vertical-align:top}img{max-width:100%;display:block;margin:.6em auto}</style></head><body>""")
        for (b in doc.blocks) when (b) {
            is Block.Heading -> sb.append("<h${b.level.coerceIn(1, 2)}>").append(Xml.esc(b.text)).append("</h${b.level.coerceIn(1, 2)}>")
            is Block.Para -> sb.append("<p style=\"text-align:justify\">").append(Xml.esc(b.text)).append("</p>")
            is Block.Rich -> {
                val al = arrayOf("left", "center", "right", "justify")[b.align.coerceIn(0, 3)]
                sb.append("<p style=\"text-align:$al\">")
                b.runs.forEach { r -> var t = Xml.esc(r.text).replace("\t", "&emsp;"); if (r.underline) t = "<u>$t</u>"; if (r.italic) t = "<i>$t</i>"; if (r.bold) t = "<b>$t</b>"; sb.append(t) }
                sb.append("</p>")
            }
            is Block.Table -> { sb.append("<table>"); b.rows.forEach { r -> sb.append("<tr>"); r.forEach { c -> sb.append("<td>").append(Xml.esc(c)).append("</td>") }; sb.append("</tr>") }; sb.append("</table>") }
            is Block.Image -> sb.append("<img src=\"data:image/jpeg;base64,").append(Base64.getEncoder().encodeToString(b.jpeg)).append("\">")
        }
        return sb.append("</body></html>").toString()
    }
}
