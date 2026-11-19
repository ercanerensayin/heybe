package com.ercan.heybe.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** CSV okuma/yazma ve basit XLSX okuma/yazma (harici kütüphanesiz). */
object Sheets {
    /** Ayırıcıyı tahmin eder: Türkçe Excel ';' kullanır. */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(20).joinToString("\n")
        return listOf(';', ',', '\t').maxByOrNull { d -> sample.count { it == d } } ?: ';'
    }

    fun parseCsv(text0: String, delimiter: Char = detectDelimiter(text0)): List<List<String>> {
        val text = text0.removePrefix("﻿")
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder()
        var inQ = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQ) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQ = false }
                else cell.append(c)
            } else when (c) {
                '"' -> inQ = true
                delimiter -> { row += cell.toString(); cell.clear() }
                '\r' -> {}
                '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    fun writeCsv(rows: List<List<String>>, delimiter: Char = ';'): String = "﻿" + rows.joinToString("\r\n") { r ->
        r.joinToString(delimiter.toString()) { v -> if (v.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v }
    } + "\r\n"

    // ---------- XLSX okuma ----------
    data class Sheet(val name: String, val rows: List<List<String>>)

    private fun dom(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    fun readXlsx(file: File): List<Sheet> = ZipFile(file).use { z ->
        fun bytes(p: String) = z.getEntry(p)?.let { z.getInputStream(it).readBytes() }
        val shared = bytes("xl/sharedStrings.xml")?.let { b ->
            val d = dom(b); val sis = d.getElementsByTagNameNS("*", "si")
            (0 until sis.length).map { i -> val ts = (sis.item(i) as org.w3c.dom.Element).getElementsByTagNameNS("*", "t"); (0 until ts.length).joinToString("") { ts.item(it).textContent } }
        } ?: emptyList()
        val wb = dom(bytes("xl/workbook.xml") ?: error("Geçerli bir Excel dosyası değil"))
        val rels = bytes("xl/_rels/workbook.xml.rels")?.let { b ->
            val d = dom(b); val rs = d.getElementsByTagNameNS("*", "Relationship")
            (0 until rs.length).associate { i -> val e = rs.item(i) as org.w3c.dom.Element; e.getAttribute("Id") to e.getAttribute("Target") }
        } ?: emptyMap()
        val sheetEls = wb.getElementsByTagNameNS("*", "sheet")
        (0 until sheetEls.length).map { i ->
            val e = sheetEls.item(i) as org.w3c.dom.Element
            val rid = e.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val target = rels[rid]?.removePrefix("/xl/")?.removePrefix("/") ?: "worksheets/sheet${i + 1}.xml"
            val sd = dom(bytes("xl/$target") ?: bytes("xl/worksheets/sheet${i + 1}.xml") ?: error("Sayfa bulunamadı"))
            val rowEls = sd.getElementsByTagNameNS("*", "row")
            val rows = (0 until rowEls.length).map { r ->
                val cells = (rowEls.item(r) as org.w3c.dom.Element).getElementsByTagNameNS("*", "c")
                val out = ArrayList<String>()
                for (k in 0 until cells.length) {
                    val c = cells.item(k) as org.w3c.dom.Element
                    val idx = c.getAttribute("r").takeIf { it.isNotEmpty() }?.let(::colIndex) ?: out.size
                    while (out.size < idx) out += ""
                    val t = c.getAttribute("t")
                    val v = c.getElementsByTagNameNS("*", "v").item(0)?.textContent ?: ""
                    out += when (t) {
                        "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> c.getElementsByTagNameNS("*", "t").let { ts -> (0 until ts.length).joinToString("") { ts.item(it).textContent } }
                        "b" -> if (v == "1") "DOĞRU" else "YANLIŞ"
                        else -> v
                    }
                }
                out
            }
            Sheet(e.getAttribute("name"), rows)
        }
    }

    // ---------- XLSX yazma ----------
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }; return sb.toString() }

    /** Sayıya benzeyen hücreler sayı olarak yazılır ("1.234,56" Türkçe biçim dahil). */
    fun numberOf(s: String): Double? {
        val t = s.trim()
        if (t.isEmpty() || t.length > 20 || (t.length > 1 && t.startsWith("0") && !t.startsWith("0,") && !t.startsWith("0."))) return null
        if (Regex("^-?\\d{1,3}(\\.\\d{3})+(,\\d+)?$").matches(t) || Regex("^-?\\d+,\\d+$").matches(t)) return t.replace(".", "").replace(',', '.').toDoubleOrNull()
        if (Regex("^-?\\d+(\\.\\d+)?$").matches(t)) return t.toDoubleOrNull()
        return null
    }

    fun writeXlsx(sheetName: String, rows: List<List<String>>, out: OutputStream) {
        val zip = ZipOutputStream(out)
        fun put(n: String, s: String) { zip.putNextEntry(ZipEntry(n)); zip.write(s.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${esc(sheetName.take(31).ifBlank { "Sayfa1" })}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                val n = numberOf(v)
                if (v.isEmpty()) {} else if (n != null) sb.append("<c r=\"$ref\"><v>$n</v></c>")
                else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        put("xl/worksheets/sheet1.xml", sb.toString())
        zip.finish()
    }
}
