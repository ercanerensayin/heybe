package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeTest {
    @Test fun karisik() { assertEquals(listOf(1, 2, 3, 5, 8, 9, 10), PageRange.parse("1-3, 5, 8-", 10)) }
    @Test fun tekrarYok() { assertEquals(listOf(2, 3, 4), PageRange.parse("2-3,3-4", 5)) }
    @Test fun basiBos() { assertEquals(listOf(1, 2), PageRange.parse("-2", 5)) }
    @Test fun hatali() {
        for (s in listOf("0", "6", "3-1", "a", "")) {
            var hata = false
            try { PageRange.parse(s, 5) } catch (e: PageRange.Error) { hata = true }
            assertTrue("hata beklenirdi: $s", hata)
        }
    }
    @Test fun etiket() {
        assertEquals("s1-3_5", PageRange.label(listOf(1, 2, 3, 5)))
        assertEquals("s4", PageRange.label(listOf(4)))
    }
}
