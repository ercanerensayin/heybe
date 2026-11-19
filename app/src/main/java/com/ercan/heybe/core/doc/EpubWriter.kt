package com.ercan.heybe.core.doc

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB 3 (EPUB 2 okuyucuları için toc.ncx ile) yazıcı. */
object EpubWriter {
    data class Chapter(val title: String, val blocks: List<Block>)

    fun chapters(doc: Doc): List<Chapter> {
        val levels = doc.blocks.filterIsInstance<Block.Heading>().map { it.level }.toSet()
        val splitLevel = when { 1 in levels -> 1; 2 in levels -> 2; else -> null }
        val result = mutableListOf<Chapter>()
        if (splitLevel != null) {
            var title = doc.title; var cur = mutableListOf<Block>()
            for (b in doc.blocks) {
                if (b is Block.Heading && b.level == splitLevel) {
                    if (cur.isNotEmpty()) result += Chapter(title, cur)
                    title = b.text; cur = mutableListOf(b)
                } else cur += b
            }
            if (cur.isNotEmpty()) result += Chapter(title, cur)
        } else {
            doc.blocks.chunked(40).forEachIndexed { i, c -> result += Chapter("Bölüm ${i + 1}", c) }
        }
        return result.ifEmpty { listOf(Chapter(doc.title, listOf(Block.Para("")))) }
    }

    fun write(doc: Doc, out: OutputStream) {
        val chs = chapters(doc)
        val uuid = UUID.randomUUID().toString()
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val images = mutableListOf<Pair<String, ByteArray>>()
        val zip = ZipOutputStream(out)

        // mimetype: ilk girdi, sıkıştırılmamış
        val mt = "application/epub+zip".toByteArray()
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
            crc = CRC32().apply { update(mt) }.value
        }); zip.write(mt); zip.closeEntry()
        fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }

        put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        put("OEBPS/style.css", "body{font-family:serif;line-height:1.5;margin:0 4%}h1{font-size:1.5em;margin:1.2em 0 .6em}h2{font-size:1.2em;margin:1em 0 .5em}p{text-indent:1.2em;margin:0 0 .4em;text-align:justify}img{max-width:100%;display:block;margin:.8em auto}")

        chs.forEachIndexed { i, ch ->
            val body = StringBuilder()
            for (b in ch.blocks) when (b) {
                is Block.Heading -> body.append("<h${b.level.coerceIn(1, 3)}>").append(Xml.esc(b.text)).append("</h${b.level.coerceIn(1, 3)}>\n")
                is Block.Para -> if (b.text.isNotBlank()) body.append("<p>").append(Xml.esc(b.text)).append("</p>\n")
                is Block.Image -> {
                    val name = "images/img${images.size + 1}.jpg"; images += name to b.jpeg
                    body.append("<img src=\"").append(name).append("\" alt=\"\"/>\n")
                }
            }
            put("OEBPS/bolum${i + 1}.xhtml", """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="tr" lang="tr">
<head><meta charset="utf-8"/><title>${Xml.esc(ch.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
$body</body>
</html>""")
        }
        images.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry("OEBPS/$name")); zip.write(bytes); zip.closeEntry() }

        val navItems = chs.mapIndexed { i, c -> "<li><a href=\"bolum${i + 1}.xhtml\">${Xml.esc(c.title)}</a></li>" }.joinToString("\n")
        put("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="tr" lang="tr">
<head><meta charset="utf-8"/><title>İçindekiler</title></head>
<body><nav epub:type="toc" id="toc"><h1>İçindekiler</h1><ol>
$navItems
</ol></nav></body></html>""")
        val ncxPoints = chs.mapIndexed { i, c ->
            "<navPoint id=\"np${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>${Xml.esc(c.title)}</text></navLabel><content src=\"bolum${i + 1}.xhtml\"/></navPoint>"
        }.joinToString("\n")
        put("OEBPS/toc.ncx", """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:uuid:$uuid"/></head>
<docTitle><text>${Xml.esc(doc.title)}</text></docTitle><navMap>
$ncxPoints
</navMap></ncx>""")
        val manifest = buildString {
            append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
            append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
            chs.indices.forEach { append("<item id=\"b${it + 1}\" href=\"bolum${it + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>\n") }
            images.forEachIndexed { i, (n, _) -> append("<item id=\"i${i + 1}\" href=\"$n\" media-type=\"image/jpeg\"/>\n") }
        }
        val spine = chs.indices.joinToString("\n") { "<itemref idref=\"b${it + 1}\"/>" }
        put("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="tr">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">urn:uuid:$uuid</dc:identifier>
<dc:title>${Xml.esc(doc.title)}</dc:title>
<dc:language>tr</dc:language>
<meta property="dcterms:modified">$modified</meta>
</metadata>
<manifest>
$manifest</manifest>
<spine toc="ncx">
$spine
</spine>
</package>""")
        zip.finish()
    }
}
