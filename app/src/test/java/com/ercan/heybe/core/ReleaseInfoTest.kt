package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {
    @Test fun okuma() {
        val r = ReleaseInfo.parse("Otomatik derlendi: abc\nversionCode=112\nversionName=0.7.112")!!
        assertEquals(112L, r.versionCode); assertEquals("0.7.112", r.versionName)
        assertTrue(r.isNewerThan(7)); assertTrue(!r.isNewerThan(112))
    }
    @Test fun eskiNot() { assertEquals(null, ReleaseInfo.parse("Otomatik derlendi: abc")) }
}
