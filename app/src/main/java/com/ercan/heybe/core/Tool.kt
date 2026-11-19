package com.ercan.heybe.core

import java.text.Normalizer
import java.util.Locale

enum class Category(val title: String) { BELGE("Belge"), MEDYA("Medya"), HESAP("Hesap"), WEB("Web") }

data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val category: Category,
    val keywords: List<String> = emptyList(),
)

object ToolRegistry {
    val tools: List<Tool> = listOf(
        Tool("pdf_birlestir", "PDF Birleştir / Böl", "Birden çok PDF'i tek dosya yap ya da sayfalara ayır", Category.BELGE, listOf("pdf", "birleştir", "böl", "sayfa")),
        Tool("pdf_sikistir", "PDF Sıkıştır", "PDF dosyasının boyutunu küçült", Category.BELGE, listOf("pdf", "sıkıştır", "küçült", "boyut")),
        Tool("gorsel_pdf", "Görselden PDF", "Fotoğrafları tek PDF'te topla", Category.BELGE, listOf("pdf", "fotoğraf", "resim", "görsel", "tarama")),
        Tool("tarayici", "Belge Tarayıcı", "Kâğıdı kenarlarını bulup düzelterek tara; PDF, metin ya da Word yap", Category.BELGE, listOf("tara", "tarayıcı", "scanner", "camscanner", "belge", "kağıt", "kâğıt", "fotokopi")),
        Tool("donustur", "Dönüştürücü", "PDF → EPUB/Word/TXT/JPG, Word ve EPUB → PDF, HEIC → JPG", Category.BELGE, listOf("dönüştür", "çevir", "epub", "word", "docx", "heic", "jpg", "png", "txt", "e-kitap")),
        Tool("ocr", "Metin Tanıma (OCR)", "Fotoğraftaki yazıyı metne çevir; IBAN ve telefonları ayıkla", Category.BELGE, listOf("ocr", "metin", "yazı", "tara", "iban", "kopyala", "ekran görüntüsü")),
        Tool("qr", "QR / Barkod", "QR ve barkod oku ya da oluştur", Category.BELGE, listOf("qr", "barkod", "kare kod", "tara")),
        Tool("video_indir", "Video İndir", "Linkten en yüksek kalitede video ya da MP3 indir", Category.MEDYA, listOf("video", "indir", "youtube", "instagram", "mp3", "müzik")),
        Tool("perde", "Perde", "Telefondaki videoları oynat: altyazı, ses dili, kaldığın yerden devam", Category.MEDYA, listOf("video", "oynatıcı", "film", "dizi", "altyazı", "mkv", "vlc", "izle")),
        Tool("medya", "Medya Araçları", "Videodan MP3 çıkar, video/ses kırp, videoyu küçült", Category.MEDYA, listOf("mp3", "ses", "kırp", "kes", "sıkıştır", "küçült", "whatsapp", "müzik")),
        Tool("gorsel_sikistir", "Görsel Sıkıştır", "Fotoğrafları küçült ve yeniden boyutlandır", Category.MEDYA, listOf("görsel", "fotoğraf", "resim", "sıkıştır", "boyut", "jpg")),
        Tool("siteler", "Siteler", "Önemli sitelerini kaydet, tek dokunuşla Chrome'da aç", Category.WEB, listOf("site", "web", "tarayıcı", "chrome", "e-devlet", "link", "adres")),
        Tool("kredi_kdv", "Kredi / KDV", "Kredi taksiti ve KDV hesapla", Category.HESAP, listOf("kredi", "kdv", "faiz", "taksit", "vergi", "hesap")),
    )

    fun byId(id: String): Tool? = tools.firstOrNull { it.id == id }

    /** Türkçe karakterlere duyarsız arama: "sikistir" de "Sıkıştır"ı bulur. */
    fun search(query: String): List<Tool> {
        val q = normalize(query)
        if (q.isBlank()) return tools
        val parts = q.split(" ").filter { it.isNotBlank() }
        return tools.filter { t ->
            val hay = normalize(t.name + " " + t.description + " " + t.keywords.joinToString(" ") + " " + t.category.title)
            parts.all { hay.contains(it) }
        }
    }

    fun normalize(s: String): String {
        val lower = s.lowercase(Locale.forLanguageTag("tr"))
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()
    }
}
