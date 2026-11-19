package com.ercan.heybe.core

/** Paylaş menüsünden gelen içeriği doğru araca yönlendirme kuralları (Android'den bağımsız). */
object ShareRouter {
    /** Sabit kısayollar: paylaş menüsünde ayrı satır olarak görünür. */
    enum class Shortcut(val alias: String, val label: String) {
        VIDEO("VideoShortcut", "Video İndir"),
        PDF("PdfShortcut", "PDF Araçları"),
        GORSEL_SIKISTIR("ImageCompressShortcut", "Görsel Sıkıştır"),
        GORSEL_PDF("ImageToPdfShortcut", "Görselden PDF");

        companion object {
            fun fromClassName(name: String?): Shortcut? = entries.firstOrNull { name?.endsWith("." + it.alias) == true }
            /** İkinci kısayol için seçilebilenler (Video İndir her zaman açık). */
            val second = listOf(PDF, GORSEL_SIKISTIR, GORSEL_PDF)
        }
    }

    sealed class Route {
        data class StartVideo(val url: String) : Route()
        data class OpenTool(val toolId: String, val text: String? = null) : Route()
        data class Choose(val toolIds: List<String>) : Route()
        data class Unsupported(val message: String) : Route()
    }

    private fun isPdf(m: String) = m.equals("application/pdf", ignoreCase = true)
    private fun isImage(m: String) = m.startsWith("image/", ignoreCase = true)
    private fun isDoc(m: String) = m.equals("application/epub+zip", true) ||
        m.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", true) ||
        m.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", true) ||
        m.equals("text/csv", true) || m.equals("text/comma-separated-values", true)
    private fun isBook(m: String) = m.equals("application/epub+zip", true) ||
        m.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", true)
    private fun isAv(m: String) = m.startsWith("video/", ignoreCase = true) || m.startsWith("audio/", ignoreCase = true)

    fun route(mimes: List<String>, text: String?, shortcut: Shortcut?): Route {
        val url = text?.let { Regex("https?://[^\\s<>\"']+").find(it)?.value?.trimEnd('.', ',', ')', ']') }
        val files = mimes.filter { it.isNotBlank() && !it.equals("text/plain", ignoreCase = true) && !it.equals("text/html", ignoreCase = true) }
        val allPdf = files.isNotEmpty() && files.all(::isPdf)
        val allImg = files.isNotEmpty() && files.all(::isImage)

        return when (shortcut) {
            Shortcut.VIDEO -> if (url != null) Route.StartVideo(url) else Route.Unsupported("Paylaşılan içerikte video linki bulunamadı.")
            Shortcut.PDF -> when {
                allImg -> Route.OpenTool("gorsel_pdf")
                allPdf && files.size > 1 -> Route.OpenTool("pdf_birlestir")
                allPdf -> Route.Choose(listOf("pdf_birlestir", "pdf_sikistir", "yazdir", "donustur"))
                else -> Route.Unsupported("PDF araçları yalnızca PDF ve görsel dosyalarla çalışır.")
            }
            Shortcut.GORSEL_SIKISTIR -> if (allImg) Route.OpenTool("gorsel_sikistir") else Route.Unsupported("Bu kısayol yalnızca görsellerle çalışır.")
            Shortcut.GORSEL_PDF -> if (allImg) Route.OpenTool("gorsel_pdf") else Route.Unsupported("Bu kısayol yalnızca görsellerle çalışır.")
            null -> when {
                files.isEmpty() && url != null -> Route.OpenTool("video_indir", url)
                files.isEmpty() && !text.isNullOrBlank() -> Route.OpenTool("cevir", text)
                files.isEmpty() -> Route.Unsupported("Paylaşılan içerik boş.")
                allPdf && files.size > 1 -> Route.Choose(listOf("pdf_birlestir", "yazdir"))
                allPdf -> Route.Choose(listOf("okuyucu", "pdf_birlestir", "pdf_sikistir", "yazdir", "donustur"))
                allImg -> Route.Choose(listOf("ocr", "gorsel_pdf", "gorsel_sikistir", "yazdir", "donustur"))
                files.size == 1 && isBook(files[0]) -> Route.Choose(listOf("okuyucu", "donustur"))
                files.all(::isDoc) -> Route.OpenTool("donustur")
                files.isNotEmpty() && files.all(::isAv) -> Route.Choose(listOf("medya", "donustur"))
                else -> Route.Unsupported("Bu dosya türleri birlikte işlenemiyor.")
            }
        }
    }
}
