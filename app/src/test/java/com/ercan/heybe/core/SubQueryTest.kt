package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SubQueryTest {
    @Test fun film() { assertEquals("The Matrix 1999", SubQuery.clean("The.Matrix.1999.1080p.BluRay.x264-YIFY.mkv")) }
    @Test fun dizi() { assertEquals("Breaking Bad S02E05", SubQuery.clean("Breaking.Bad.S02E05.720p.HDTV.x264.mp4")) }
    @Test fun yalin() { assertEquals("Kuru Otlar Üstüne", SubQuery.clean("Kuru_Otlar_Üstüne.mkv")) }
    @Test fun yilsiz() { assertEquals("Film Adi", SubQuery.clean("Film Adi WEB-DL 1080p.mp4")) }
}
