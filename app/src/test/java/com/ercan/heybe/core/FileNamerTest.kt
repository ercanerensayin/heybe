package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamerTest {
    @Test fun videoAdi() { assertEquals("Video_Adi_1080p.mp4", FileNamer.video("Video Adı", "1080p", "mp4")) }
    @Test fun turkceVeIsaretler() { assertEquals("Isik_Sogus_Cicek_Yagmuru.mkv", FileNamer.video("Işık / Söğüş: \"Çiçek\" Yağmuru!", null, ".mkv")) }
    @Test fun turetilmis() { assertEquals("rapor_birlesik.pdf", FileNamer.derived("/sdcard/rapor.pdf", "birleşik", "pdf")) }
    @Test fun bosAd() { assertEquals("dosya_sikistirilmis.jpg", FileNamer.derived("???", "sıkıştırılmış", "jpg")) }
    @Test fun uzunAdKesilir() { assertEquals(80, FileNamer.clean("a".repeat(200)).length) }
    @Test fun benzersiz() {
        val var_ = setOf("a.pdf", "a (2).pdf")
        assertEquals("a (3).pdf", FileNamer.unique("a.pdf") { it in var_ })
        assertEquals("b.pdf", FileNamer.unique("b.pdf") { it in var_ })
    }
}
