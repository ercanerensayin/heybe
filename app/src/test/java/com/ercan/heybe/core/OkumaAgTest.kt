package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkumaAgTest {
    @Test fun kelimeler() { assertEquals(listOf("Merhaba,", "dünya!", "Nasılsın?"), Rsvp.words("  Merhaba,\n dünya!\tNasılsın? ")) }
    @Test fun odak() {
        assertEquals(0, Rsvp.focusIndex("a"))
        assertEquals(1, Rsvp.focusIndex("ev"))
        assertEquals(2, Rsvp.focusIndex("kitaplık"))
        assertEquals(2, Rsvp.focusIndex("«kitap»"))
    }
    @Test fun sure() {
        assertEquals(200L, Rsvp.delayMs("ev", 300))
        assertEquals(440L, Rsvp.delayMs("bitti.", 300))
        assertEquals(300L, Rsvp.delayMs("ama,", 300))
        assertEquals(260L, Rsvp.delayMs("kitaplıklar", 300))
        assertEquals(2, Rsvp.minutesLeft(301, 300))
        assertEquals(0, Rsvp.minutesLeft(0, 300))
    }
    @Test fun wifiQr() {
        assertEquals("WIFI:T:WPA;S:Ev Ağı;P:a\\;b\\:c\\\\d;;", WifiQr.build("Ev Ağı", "a;b:c\\d", WifiQr.Security.WPA))
        assertEquals("WIFI:T:nopass;S:Misafir;H:true;;", WifiQr.build("Misafir", "x", WifiQr.Security.NONE, hidden = true))
        assertEquals("WPA şifresi 8–63 karakter olmalı.", WifiQr.problem("Ev", "123", WifiQr.Security.WPA))
        assertEquals(null, WifiQr.problem("Ev", "12345678", WifiQr.Security.WPA))
        assertEquals("Ev", WifiQr.cleanSsid("\"Ev\""))
        assertEquals(null, WifiQr.cleanSsid("<unknown ssid>"))
    }
    @Test fun altAg() {
        val h = NetMath.hosts("192.168.1.34", 24)
        assertEquals(254, h.size); assertEquals("192.168.1.1", h.first()); assertEquals("192.168.1.254", h.last())
        assertEquals(254, NetMath.hosts("10.0.5.9", 16).size)
        assertEquals(listOf("192.168.1.33", "192.168.1.34"), NetMath.hosts("192.168.1.34", 30))
        assertEquals(emptyList<String>(), NetMath.hosts("abc", 24))
    }
    @Test fun hiz() {
        assertEquals(80.0, NetMath.mbps(10_000_000, 1000), 1e-9)
        assertEquals(3L, NetMath.median(listOf(5, 1, 3)))
        assertEquals(4L, NetMath.median(listOf(5, 1, 3, 9)))
        assertEquals("Yazıcı", NetMath.guess(setOf(80, 9100), false, false))
        assertEquals("Modem / yönlendirici", NetMath.guess(setOf(80), true, false))
    }
    @Test fun markdown() {
        val l = MiniMd.parse("# Başlık\n- [x] süt\n- ekmek\n2. iki\n> alıntı\n---\n\nmetin")
        assertEquals(MiniMd.Line.H(1, "Başlık"), l[0])
        assertEquals(MiniMd.Line.Bullet("süt", true), l[1])
        assertEquals(MiniMd.Line.Bullet("ekmek"), l[2])
        assertEquals(MiniMd.Line.Num("2", "iki"), l[3])
        assertEquals(MiniMd.Line.Quote("alıntı"), l[4])
        assertTrue(l[5] === MiniMd.Line.Rule && l[6] === MiniMd.Line.Blank)
        assertEquals(listOf(MiniMd.Span("a "), MiniMd.Span("kalın", bold = true), MiniMd.Span(" ve "), MiniMd.Span("eğik", italic = true), MiniMd.Span(" "), MiniMd.Span("kod", code = true)),
            MiniMd.spans("a **kalın** ve *eğik* `kod`"))
        assertEquals(listOf(MiniMd.Span("dosya_adi_bu")), MiniMd.spans("dosya_adi_bu"))
    }
    @Test fun radyo() {
        val l = listOf(Station("TRT FM", "https://a/b", "https://l", "u1"), Station("Ad\tX", "http://c", "", ""))
        val back = RadioStations.decode(RadioStations.encode(l))
        assertEquals("TRT FM", back[0].name); assertEquals("u1", back[0].id); assertEquals("Ad X", back[1].name)
        assertTrue(RadioStations.validUrl("http://x.com:8000/stream"))
        assertEquals(false, RadioStations.validUrl("x.com"))
        assertEquals(1, RadioStations.dedupe(listOf(Station("A", "http://x"), Station("a ", "http://X"), Station("B", ""))).size)
    }
}
