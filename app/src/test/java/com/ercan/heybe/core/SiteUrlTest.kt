package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteUrlTest {
    @Test fun sema() { assertEquals("https://turkiye.gov.tr", SiteUrl.normalize(" turkiye.gov.tr ")) }
    @Test fun korunur() { assertEquals("http://192.168.1.1/admin", SiteUrl.normalize("http://192.168.1.1/admin")) }
    @Test fun gecersiz() {
        assertEquals(null, SiteUrl.normalize("merhaba"))
        assertEquals(null, SiteUrl.normalize("ftp://a.com"))
        assertEquals(null, SiteUrl.normalize(""))
    }
    @Test fun ad() { assertEquals("sgk.gov.tr", SiteUrl.defaultName("https://www.sgk.gov.tr/giris")) }
}
