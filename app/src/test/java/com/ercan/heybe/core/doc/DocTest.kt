package com.ercan.heybe.core.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocTest {
    private fun line(t: String, top: Float, size: Float = 11f, right: Float = 500f) = PageItem.Text(Line(t, size, top, top + size, 50f, right))

    private fun page(n: Int, vararg body: PageItem) = Page(
        listOf(line("Kitabın Adı — Üst Bilgi", 20f, 9f)) + body.toList() + listOf(line("$n", 820f, 9f, 60f)), 595f, 842f)

    private val pages = listOf(
        page(1, line("Birinci Bölüm", 100f, 20f, 300f),
            line("Bu ilk paragrafın ilk satırıdır ve sayfa genişliği boyunca uza-", 140f),
            line("nır; ikinci satırda devam eder.", 154f, right = 300f),
            line("İkinci paragraf yeni bir satırda başlar ve sayfanın sonuna kadar", 175f),
            line("gider, cümle bitmeden sayfa sona erer ve", 189f)),
        page(2, line("sonraki sayfada tamamlanır.", 100f, right = 250f),
            line("Alt Başlık", 130f, 14f, 200f),
            line("Son paragraf.", 160f, right = 150f)),
        page(3, line("İkinci Bölüm", 100f, 20f, 300f), line("Kısa metin.", 140f, right = 150f)),
    )

    @Test fun akis() {
        val d = Reflow.run("Kitap", pages)
        val b = d.blocks
        assertEquals(Block.Heading(1, "Birinci Bölüm"), b[0])
        assertEquals(Block.Para("Bu ilk paragrafın ilk satırıdır ve sayfa genişliği boyunca uzanır; ikinci satırda devam eder."), b[1])
        assertEquals(Block.Para("İkinci paragraf yeni bir satırda başlar ve sayfanın sonuna kadar gider, cümle bitmeden sayfa sona erer ve sonraki sayfada tamamlanır."), b[2])
        assertEquals(Block.Heading(2, "Alt Başlık"), b[3])
        assertEquals(Block.Para("Son paragraf."), b[4])
        assertEquals(Block.Heading(1, "İkinci Bölüm"), b[5])
        assertTrue("üst bilgi/sayfa no kalmamalı", b.none { it is Block.Para && (it.text.contains("Üst Bilgi") || it.text.trim().matches(Regex("\\d+"))) })
    }

    @Test fun tireBuyukHarfteKalmaz() {
        val sb = StringBuilder("Ankara-"); Reflow.appendLine(sb, "İstanbul hattı")
        assertEquals("Ankara- İstanbul hattı", sb.toString())
    }

    private val codec = DocReaders.ImageCodec { bytes -> Block.Image(bytes, 100, 50) }
    private val sample = Doc("Deneme & Kitap", listOf(
        Block.Heading(1, "Giriş"), Block.Para("Şu <özel> karakterler & \"tırnak\" korunmalı: ğüşıöç ĞÜŞİÖÇ."),
        Block.Image(byteArrayOf(-1, -40, -1, -39), 400, 200), Block.Heading(2, "Alt"), Block.Para("İkinci paragraf."),
        Block.Heading(1, "Sonuç"), Block.Para("Bitti.")))

    @Test fun docxGidisDonus() {
        val f = File.createTempFile("heybe", ".docx"); f.outputStream().use { DocxWriter.write(sample, it) }
        val back = DocReaders.docx(f, "x", codec).blocks
        assertEquals(sample.blocks.filter { it !is Block.Image }, back.filter { it !is Block.Image })
        assertEquals(1, back.count { it is Block.Image })
        File("/tmp/heybe_test.docx").writeBytes(f.readBytes())
    }

    @Test fun epubGidisDonus() {
        val f = File.createTempFile("heybe", ".epub"); f.outputStream().use { EpubWriter.write(sample, it) }
        val back = DocReaders.epub(f, "x", codec)
        assertEquals("Deneme & Kitap", back.title)
        assertEquals(sample.blocks.filter { it !is Block.Image }, back.blocks.filter { it !is Block.Image })
        assertEquals(1, back.blocks.count { it is Block.Image })
        assertEquals(2, EpubWriter.chapters(sample).size)
        File("/tmp/heybe_test.epub").writeBytes(f.readBytes())
    }

    @Test fun yol() { assertEquals("OEBPS/images/a.jpg", DocReaders.resolve("OEBPS/text/", "../images/a.jpg")) }
}
