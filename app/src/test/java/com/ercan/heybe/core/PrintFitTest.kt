package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintFitTest {
    @Test fun dikeyGorselDikeySayfa() {
        val p = PrintFit.place(1000, 2000, 10f, 20f, 500f, 800f)
        assertEquals(false, p.rotate)
        assertEquals(400.0, p.width.toDouble(), 0.01)
        assertEquals(800.0, p.height.toDouble(), 0.01)
        assertEquals(60.0, p.left.toDouble(), 0.01)
        assertEquals(20.0, p.top.toDouble(), 0.01)
    }
    @Test fun yatayGorselDondurulur() {
        val p = PrintFit.place(4000, 3000, 0f, 0f, 595f, 842f)
        assertTrue(p.rotate)
        assertEquals(595.0, p.width.toDouble(), 0.01)
        assertEquals(793.33, p.height.toDouble(), 0.01)
        assertEquals(24.33, p.top.toDouble(), 0.01)
    }
    @Test fun kareDondurulmez() { assertEquals(false, PrintFit.place(500, 500, 0f, 0f, 595f, 842f).rotate) }
    @Test fun dondurmeKapali() { assertEquals(false, PrintFit.place(4000, 3000, 0f, 0f, 595f, 842f, autoRotate = false).rotate) }
    @Test fun karisikSecim() {
        assertEquals(null, PrintFit.problem(listOf("application/pdf", "application/pdf")))
        assertEquals(null, PrintFit.problem(listOf("image/jpeg", "image/png")))
        assertEquals("PDF'leri ve görselleri ayrı ayrı yazdır.", PrintFit.problem(listOf("application/pdf", "image/png")))
        assertEquals("Yalnızca PDF ve görseller yazdırılabilir.", PrintFit.problem(listOf("video/mp4")))
    }
}
