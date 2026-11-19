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
    @Test fun linksizMetinCevirmene() { assertEquals(Route.OpenTool("cevir", "merhaba"), ShareRouter.route(listOf("text/plain"), "merhaba", null)) }
    @Test fun tekPdfSecim() { assertEquals(Route.Choose(listOf("pdf_birlestir", "pdf_sikistir", "yazdir", "donustur")), ShareRouter.route(listOf("application/pdf"), null, null)) }
    @Test fun cokPdfBirlestir() { assertEquals(Route.Choose(listOf("pdf_birlestir", "yazdir")), ShareRouter.route(listOf("application/pdf", "application/pdf"), null, null)) }
    @Test fun gorseller() { assertEquals(Route.Choose(listOf("ocr", "gorsel_pdf", "gorsel_sikistir", "yazdir", "donustur")), ShareRouter.route(listOf("image/jpeg", "image/png"), null, null)) }
    @Test fun karisik() { assertTrue(ShareRouter.route(listOf("image/jpeg", "application/pdf"), null, null) is Route.Unsupported) }
    @Test fun pdfKisayoluGorsel() { assertEquals(Route.OpenTool("gorsel_pdf"), ShareRouter.route(listOf("image/jpeg"), null, Shortcut.PDF)) }
    @Test fun videoMedyaya() { assertEquals(Route.Choose(listOf("medya", "donustur")), ShareRouter.route(listOf("video/mp4"), null, null)) }
    @Test fun epubDonusture() { assertEquals(Route.OpenTool("donustur"), ShareRouter.route(listOf("application/epub+zip"), null, null)) }
    @Test fun csvDonusture() { assertEquals(Route.OpenTool("donustur"), ShareRouter.route(listOf("text/csv"), null, null)) }
    @Test fun aliasAdi() { assertEquals(Shortcut.PDF, Shortcut.fromClassName("com.ercan.heybe.share.PdfShortcut")) }
}
