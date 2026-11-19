package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MediaMathTest {
    @Test fun zaman() {
        assertEquals(62500L, MediaMath.ffmpegTime("size= 1024kB time=00:01:02.50 bitrate=128.0kbits/s"))
        assertEquals(3723000L, MediaMath.ffmpegTime("time=01:02:03 x"))
        assertEquals(null, MediaMath.ffmpegTime("Duration: N/A"))
    }
    @Test fun damga() { assertEquals("01:02:03.045", MediaMath.stamp(3723045)) }
    @Test fun tahmin() { assertTrue(abs(MediaMath.estimateMb(60_000, 1_000_000) - 8.07) < 0.05) }
    @Test fun buyutmeYok() { assertTrue(!MediaMath.shouldScale(480, 720)); assertTrue(MediaMath.shouldScale(1080, 720)) }
}
