package com.ercan.heybe.core.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class UdfTest {
    private val codec = DocReaders.ImageCodec { bytes -> Block.Image(bytes, 100, 50) }
    private val doc = Doc("Dilekçe", listOf(
        Block.Heading(1, "ANKARA 3. ASLİYE HUKUK MAHKEMESİ'NE"),
        Block.Rich(listOf(Run("DAVACI", bold = true), Run("\t: Ali Veli")), 0),
        Block.Para("Müvekkil ile davalı arasında \"]]>\" karakterleri & <özel> işaretler bulunan bir sözleşme imzalanmıştır."),
        Block.Table(listOf(listOf("Tarih", "Tutar"), listOf("19.11.2026", "1.250,50 TL"))),
        Block.Image(byteArrayOf(-1, -40, -1, -39), 400, 200),
        Block.Rich(listOf(Run("Saygılarımla,", italic = true)), 2),
    ))

    @Test fun gidisDonus() {
        val f = File.createTempFile("heybe", ".udf"); f.outputStream().use { Udf.write(doc, it) }
        File("/tmp/heybe_test.udf").writeBytes(f.readBytes())
        val (back, info) = Udf.read(f, "x", codec)
        assertTrue(!info.signed)
        val b = back.blocks
        assertEquals(Block.Rich(listOf(Run("ANKARA 3. ASLİYE HUKUK MAHKEMESİ'NE", bold = true)), 1), b[0])
        assertEquals(Block.Rich(listOf(Run("DAVACI", bold = true), Run("\t: Ali Veli")), 0), b[1])
        assertEquals(Block.Rich(listOf(Run((doc.blocks[2] as Block.Para).text)), 3), b[2])
        assertEquals(doc.blocks[3], b[3])
        assertTrue(b[4] is Block.Image)
        assertEquals(doc.blocks[5], b[5])
    }

    @Test fun ofsetlerTutarli() {
        val f = File.createTempFile("heybe", ".udf"); f.outputStream().use { Udf.write(doc, it) }
        val xml = ZipFile(f).use { z -> z.getInputStream(z.getEntry("content.xml")).readBytes().toString(Charsets.UTF_8) }
        val pool = Regex("<content><!\\[CDATA\\[(.*?)]]></content>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1].replace("]]]]><![CDATA[>", "]]>")
        // Elemanlar boşluksuz ve çakışmasız; son satır sonu dışarıda kalır
        var expect = 0
        Regex("startOffset=\"(\\d+)\" length=\"(\\d+)\"").findAll(xml).forEach { m ->
            assertEquals(expect, m.groupValues[1].toInt()); expect += m.groupValues[2].toInt()
        }
        assertEquals(pool.length - 1, expect)
        assertTrue(pool.endsWith("\n"))
    }

    /** UYAP Doküman Editörü'nün yazdığı yapıya uygun elle kurulmuş örnek (üst bilgi, space, tab, boş paragraf, liste, tablo, imza). */
    @Test fun uyapOrnegi() {
        val pool = "BAŞLIK\nİSTANBUL\t\tADLİYESİ\nKalın kısım normal kısım\n\u200B\nBirinci madde\nİkinci madde\nA\nB\nAlt bilgi\n"
        val xml = """<?xml version="1.0" encoding="UTF-8" ?>
<template format_id="1.8">
<content><![CDATA[$pool]]></content>
<properties><pageFormat mediaSizeName="1" leftMargin="42.5" rightMargin="28.3" topMargin="14.1" bottomMargin="14.1" paperOrientation="1" headerFOffset="20.0" footerFOffset="20.0" /></properties>
<elements resolver="hvl-default">
<header><paragraph Alignment="1"><content startOffset="0" length="7" family="Times New Roman" size="12" bold="true" /></paragraph></header>
<paragraph Alignment="1" LeftIndent="0.0" RightIndent="0.0"><content startOffset="7" length="8" bold="true" /><tab startOffset="15" length="1" /><tab startOffset="16" length="1" /><content startOffset="17" length="9" bold="true" /></paragraph>
<paragraph Alignment="3" LeftIndent="0.0" RightIndent="0.0" FirstLineIndent="42.857143"><content startOffset="26" length="11" bold="true" /><space startOffset="37" length="1" /><content startOffset="38" length="13" /></paragraph>
<paragraph Alignment="0" LeftIndent="0.0" RightIndent="0.0"><content startOffset="51" length="2" family="Times New Roman" size="10" /></paragraph>
<paragraph Numbered="true" NumberType="NUMBER_TYPE_NUMBER_DOT" ListId="1" ListLevel="0" Alignment="0"><content startOffset="53" length="14" /></paragraph>
<paragraph Numbered="true" NumberType="NUMBER_TYPE_NUMBER_DOT" ListId="1" ListLevel="0" Alignment="0"><content startOffset="67" length="13" /></paragraph>
<table tableName="Sabit" columnCount="2" columnSpans="200.0,200.0" border="borderCell"><row rowName="row1" rowType="dataRow"><cell><paragraph><content startOffset="80" length="2" /></paragraph></cell><cell><paragraph><content startOffset="82" length="2" /></paragraph></cell></row></table>
<footer pageNumber-spec="BSP32_40"><paragraph><content startOffset="84" length="10" /></paragraph></footer>
</elements>
<styles><style name="hvl-default" family="Times New Roman" size="12" description="Gövde" /></styles>
</template>"""
        val f = File.createTempFile("heybe", ".udf")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("content.xml")); z.write(xml.toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("sign.sgn")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
        }
        val (d, info) = Udf.read(f, "ornek", codec)
        assertTrue(info.signed)
        val b = d.blocks
        assertEquals(Block.Rich(listOf(Run("BAŞLIK", bold = true)), 1), b[0])
        assertEquals(Block.Rich(listOf(Run("İSTANBUL", bold = true), Run("\t\t"), Run("ADLİYESİ", bold = true)), 1), b[1])
        assertEquals(Block.Rich(listOf(Run("Kalın kısım", bold = true), Run(" normal kısım")), 3), b[2])
        assertEquals(Block.Para("1. Birinci madde"), b[3])
        assertEquals(Block.Para("2. İkinci madde"), b[4])
        assertEquals(Block.Table(listOf(listOf("A", "B"))), b[5])
        assertEquals(Block.Para("Alt bilgi"), b[6])
        assertEquals(7, b.size)
    }
}
