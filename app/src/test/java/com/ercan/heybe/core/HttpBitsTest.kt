package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpBitsTest {
    @Test fun istek() {
        val r = HttpBits.parseRequestLine("POST /a1b2/yukle?ad=%C3%87ay%20listesi.pdf HTTP/1.1")!!
        assertEquals("POST", r.method); assertEquals("/a1b2/yukle", r.path); assertEquals("Çay listesi.pdf", r.query["ad"])
    }
    @Test fun baslik() {
        assertEquals("attachment; filename=\"_ay listesi.pdf\"; filename*=UTF-8''%C3%87ay%20listesi.pdf", HttpBits.contentDisposition("Çay listesi.pdf"))
    }
    @Test fun guvenliAd() {
        assertEquals("passwd", HttpBits.safeName("../../etc/passwd"))
        assertEquals("a_b.txt", HttpBits.safeName("a:b.txt"))
        assertEquals("dosya", HttpBits.safeName(".."))
    }
    @Test fun adres() {
        assertEquals("192.168.1.34", HttpBits.pickAddress(listOf("rmnet0" to "10.20.1.5", "wlan0" to "192.168.1.34")))
        assertEquals("192.168.43.1", HttpBits.pickAddress(listOf("rmnet0" to "100.64.2.1", "ap0" to "192.168.43.1")))
        assertEquals(null, HttpBits.pickAddress(listOf("rmnet0" to "100.64.2.1")))
    }
}
