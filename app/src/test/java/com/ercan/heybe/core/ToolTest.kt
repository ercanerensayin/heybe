package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTest {
    @Test fun aramaTurkceKarakterlereDuyarsiz() {
        val ids = ToolRegistry.search("sikistir").map { it.id }
        assertTrue("pdf_sikistir" in ids)
        assertTrue("gorsel_sikistir" in ids)
    }
    @Test fun bosAramaHepsiniDondurur() { assertEquals(ToolRegistry.tools.size, ToolRegistry.search("  ").size) }
    @Test fun cokKelimeliArama() { assertEquals(listOf("pdf_sikistir"), ToolRegistry.search("pdf küçült").map { it.id }) }
    @Test fun buyukIHarfi() { assertTrue(ToolRegistry.search("İNDİR").any { it.id == "video_indir" }) }
    @Test fun sonKullanilanlar() {
        var l = listOf<String>()
        for (id in listOf("a", "b", "c", "a", "d", "e")) l = RecentList.push(l, id)
        assertEquals(listOf("e", "d", "a", "c"), l)
    }
}
