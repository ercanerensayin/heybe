package com.ercan.heybe.core

import com.ercan.heybe.core.ShareRouter.Route
import com.ercan.heybe.core.ShareRouter.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRouterTest {
    private val yt = "Bak: https://youtu.be/x1"
    @Test fun videoKisayoluDogrudanBaslar() { assertEquals(Route.StartVideo("https://youtu.be/x1"), ShareRouter.route(listOf("text/plain"), yt, Shortcut.VIDEO)) }
    @Test fun anaGirisLinkiVideoEkraninaAcar() { assertEquals(Route.OpenTool("video_indir", "https://youtu.be/x1"), ShareRouter.route(listOf("text/plain"), yt, null)) }
    @Test fun linksizMetin() { assertTrue(ShareRouter.route(listOf("text/plain"), "merhaba", null) is Route.Unsupported) }
    @Test fun tekPdfSecim() { assertEquals(Route.Choose(listOf("pdf_birlestir", "pdf_sikistir")), ShareRouter.route(listOf("application/pdf"), null, null)) }
    @Test fun cokPdfBirlestir() { assertEquals(Route.OpenTool("pdf_birlestir"), ShareRouter.route(listOf("application/pdf", "application/pdf"), null, null)) }
    @Test fun gorseller() { assertEquals(Route.Choose(listOf("ocr", "gorsel_pdf", "gorsel_sikistir")), ShareRouter.route(listOf("image/jpeg", "image/png"), null, null)) }
    @Test fun karisik() { assertTrue(ShareRouter.route(listOf("image/jpeg", "application/pdf"), null, null) is Route.Unsupported) }
    @Test fun pdfKisayoluGorsel() { assertEquals(Route.OpenTool("gorsel_pdf"), ShareRouter.route(listOf("image/jpeg"), null, Shortcut.PDF)) }
    @Test fun videoMedyaya() { assertEquals(Route.OpenTool("medya"), ShareRouter.route(listOf("video/mp4"), null, null)) }
    @Test fun aliasAdi() { assertEquals(Shortcut.PDF, Shortcut.fromClassName("com.ercan.heybe.share.PdfShortcut")) }
}
