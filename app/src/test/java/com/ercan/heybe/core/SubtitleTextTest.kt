package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextTest {
    @Test fun utf8() { assertEquals("Şişli ığdır", SubtitleText.decode("Şişli ığdır".toByteArray(Charsets.UTF_8))) }
    @Test fun bom() { assertEquals("Çay", SubtitleText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Çay".toByteArray())) }
    @Test fun windows1254() {
        val b = "Ağır şeyler, ılık İstanbul".toByteArray(charset("windows-1254"))
        assertEquals("Ağır şeyler, ılık İstanbul", SubtitleText.decode(b))
    }
    @Test fun srtKaydir() {
        val s = "1\n00:00:01,500 --> 00:00:03,000\nMerhaba 12:00:00,000\n"
        assertEquals("1\n00:00:01,700 --> 00:00:03,200\nMerhaba 12:00:00,000\n", SubtitleText.shift(s, 200, SubtitleText.Format.SRT))
    }
    @Test fun srtSifirAlti() { assertTrue(SubtitleText.shift("00:00:00,100 --> 00:00:01,000", -500, SubtitleText.Format.SRT).startsWith("00:00:00,000 -->")) }
    @Test fun vtt() { assertEquals("01:00:00.050 --> 01:00:01.050", SubtitleText.shift("00:59:59.950 --> 01:00:00.950", 100, SubtitleText.Format.VTT)) }
    @Test fun ass() {
        val s = "Style: x\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,Selam"
        assertEquals("Style: x\nDialogue: 0,0:00:00.90,0:00:02.40,Default,,0,0,0,,Selam", SubtitleText.shift(s, -100, SubtitleText.Format.ASS))
    }
    @Test fun bicim() { assertEquals(SubtitleText.Format.ASS, SubtitleText.formatOf("Film.SSA")) }
    @Test fun kurallar() {
        assertTrue(PlaybackRules.isWatched(91, 100))
        assertEquals(0L, PlaybackRules.resumeAt(3000, 600000, false))
        assertEquals(120000L, PlaybackRules.resumeAt(120000, 600000, false))
        assertEquals(0L, PlaybackRules.resumeAt(120000, 600000, true))
        assertEquals("1:02:03", PlaybackRules.clock(3723000)); assertEquals("4:05", PlaybackRules.clock(245000))
    }
}
