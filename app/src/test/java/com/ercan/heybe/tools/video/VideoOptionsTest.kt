package com.ercan.heybe.tools.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOptionsTest {
    @Test fun ayristir() {
        assertEquals(VideoOptions.Parsed("Güzel Video", "1080p", "mp4"), VideoOptions.parse("Güzel Video__HEYBE__1080.mp4"))
        assertEquals(VideoOptions.Parsed("Konser", "4K", "mkv"), VideoOptions.parse("Konser__HEYBE__2160.mkv"))
        assertEquals(VideoOptions.Parsed("Şarkı", null, "mp3"), VideoOptions.parse("Şarkı__HEYBE__NA.mp3"))
        assertEquals(VideoOptions.Parsed("eski", null, "mp4"), VideoOptions.parse("eski.mp4"))
    }
    @Test fun link() {
        assertEquals("https://youtu.be/abc123", VideoOptions.extractUrl("Şuna bak: https://youtu.be/abc123."))
        assertEquals(null, VideoOptions.extractUrl("link yok"))
    }
    @Test fun hata() {
        assertEquals("Unsupported URL: x", VideoOptions.shortError("[generic] x\nWARNING: y\nERROR: Unsupported URL: x\n"))
    }
    @Test fun mime() { assertEquals("video/x-matroska", VideoOptions.mime("MKV")) }
}
