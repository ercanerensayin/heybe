package com.ercan.heybe.tools.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.ercan.heybe.core.FileNamer
import com.ercan.heybe.core.PageRange
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.Recorder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bir işlemin sonucu: kaydedilen dosyalar ve kullanıcıya gösterilecek özet. */
data class PdfResult(val files: List<FileStore.Saved>, val summary: String)

class PdfUserError(message: String) : Exception(message)

enum class CompressLevel(val label: String, val quality: Float, val maxSide: Int) {
    HAFIF("Hafif (kalite öncelikli)", 0.85f, 2400),
    ORTA("Orta (önerilen)", 0.65f, 1700),
    GUCLU("Güçlü (en küçük dosya)", 0.45f, 1200),
}

object PdfEngine {
    private const val MIME = "application/pdf"
    private fun mem() = MemoryUsageSetting.setupTempFileOnly()

    fun init(ctx: Context) = PDFBoxResourceLoader.init(ctx.applicationContext)

    fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "dosya"

    private fun load(ctx: Context, uri: Uri): PDDocument {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw PdfUserError("Dosya açılamadı: ${displayName(ctx, uri)}")
        return try {
            input.use { PDDocument.load(it, mem()) }
        } catch (e: InvalidPasswordException) {
            throw PdfUserError("Şifreli PDF desteklenmiyor: ${displayName(ctx, uri)}")
        } catch (e: Exception) {
            throw PdfUserError("PDF okunamadı (${displayName(ctx, uri)}): ${e.message}")
        }
    }

    fun pageCount(ctx: Context, uri: Uri): Int = load(ctx, uri).use { it.numberOfPages }

    private suspend fun saveDoc(ctx: Context, toolId: String, doc: PDDocument, name: String): FileStore.Saved {
        val saved = FileStore.save(ctx, FileStore.Folder.PDF, name, MIME) { doc.save(it) }
        Recorder.done(ctx, toolId, saved, MIME)
        return saved
    }

    suspend fun merge(ctx: Context, uris: List<Uri>): PdfResult {
        if (uris.size < 2) throw PdfUserError("Birleştirmek için en az 2 PDF seç.")
        val docs = uris.map { load(ctx, it) }
        try {
            val out = PDDocument(mem())
            out.use {
                val merger = PDFMergerUtility()
                docs.forEach { d -> merger.appendDocument(out, d) }
                val name = FileNamer.derived(displayName(ctx, uris.first()), "birlesik", "pdf")
                val saved = saveDoc(ctx, "pdf_birlestir", out, name)
                return PdfResult(listOf(saved), "${uris.size} dosya, toplam ${out.numberOfPages} sayfa birleştirildi.")
            }
        } finally {
            docs.forEach { runCatching { it.close() } }
        }
    }

    /** Seçilen sayfaları tek dosyada toplar. */
    suspend fun extract(ctx: Context, uri: Uri, range: String): PdfResult = load(ctx, uri).use { src ->
        val pages = try { PageRange.parse(range, src.numberOfPages) } catch (e: PageRange.Error) { throw PdfUserError(e.message ?: "Geçersiz aralık") }
        PDDocument(mem()).use { out ->
            pages.forEach { out.importPage(src.getPage(it - 1)) }
            val saved = saveDoc(ctx, "pdf_birlestir", out, FileNamer.derived(displayName(ctx, uri), PageRange.label(pages), "pdf"))
            PdfResult(listOf(saved), "${pages.size} sayfa ayrı bir PDF'e çıkarıldı.")
        }
    }

    /** Her sayfayı ayrı bir dosya yapar. */
    suspend fun splitEach(ctx: Context, uri: Uri, onProgress: (Int, Int) -> Unit): PdfResult = load(ctx, uri).use { src ->
        val n = src.numberOfPages
        if (n > 200) throw PdfUserError("Belge $n sayfa; her sayfayı ayrı dosya yapmak için en fazla 200 sayfa destekleniyor. Sayfa aralığı kullan.")
        val base = displayName(ctx, uri)
        val files = (1..n).map { i ->
            onProgress(i, n)
            PDDocument(mem()).use { out ->
                out.importPage(src.getPage(i - 1))
                saveDoc(ctx, "pdf_birlestir", out, FileNamer.derived(base, "s$i", "pdf"))
            }
        }
        PdfResult(files, "$n sayfa, $n ayrı PDF olarak kaydedildi.")
    }

    /** Metni koruyarak yalnızca görselleri yeniden sıkıştırır. */
    suspend fun compress(ctx: Context, uri: Uri, level: CompressLevel, onProgress: (Int, Int) -> Unit): PdfResult {
        val originalSize = runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        return load(ctx, uri).use { doc ->
            val seen = HashSet<COSName>()
            var changed = 0
            val n = doc.numberOfPages
            doc.pages.forEachIndexed { i, page ->
                onProgress(i + 1, n)
                page.resources?.let { changed += recompress(doc, it, level, seen, 0) }
            }
            val name = FileNamer.derived(displayName(ctx, uri), "sikistirilmis", "pdf")
            val saved = saveDoc(ctx, "pdf_sikistir", doc, name)
            val summary = when {
                changed == 0 -> "Belgede sıkıştırılabilecek görsel bulunamadı; çoğunlukla metinden oluşan PDF'ler zaten küçüktür."
                originalSize > 0 && saved.size >= originalSize -> "Görseller zaten iyi sıkıştırılmış; dosya küçülmedi."
                originalSize > 0 -> "$changed görsel sıkıştırıldı: ${mb(originalSize)} → ${mb(saved.size)} (%${100 - (saved.size * 100 / originalSize)} küçüldü)."
                else -> "$changed görsel sıkıştırıldı, yeni boyut ${mb(saved.size)}."
            }
            PdfResult(listOf(saved), summary)
        }
    }

    private fun recompress(doc: PDDocument, res: PDResources, level: CompressLevel, seen: MutableSet<COSName>, depth: Int): Int {
        if (depth > 5) return 0
        var count = 0
        for (name in res.xObjectNames.toList()) {
            val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (x) {
                is PDFormXObject -> x.resources?.let { count += recompress(doc, it, level, seen, depth + 1) }
                is PDImageXObject -> {
                    if (x.softMask != null || x.isStencil) continue
                    val bmp = runCatching { x.image }.getOrNull() ?: continue
                    val scaled = scaleDown(bmp, level.maxSide)
                    val jpg = runCatching { JPEGFactory.createFromImage(doc, scaled, level.quality) }.getOrNull() ?: continue
                    val oldLen = runCatching { x.cosObject.length }.getOrDefault(Long.MAX_VALUE)
                    val newLen = runCatching { jpg.cosObject.length }.getOrDefault(0L)
                    if (newLen in 1 until oldLen) { res.put(name, jpg); count++ }
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                }
            }
        }
        return count
    }

    private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val side = max(bmp.width, bmp.height)
        if (side <= maxSide) return bmp
        val f = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).roundToInt().coerceAtLeast(1), (bmp.height * f).roundToInt().coerceAtLeast(1), true)
    }

    /** Görselleri A4 sayfalara yerleştirip tek PDF yapar (EXIF yönü düzeltilir). */
    suspend fun imagesToPdf(ctx: Context, uris: List<Uri>, onProgress: (Int, Int) -> Unit): PdfResult {
        if (uris.isEmpty()) throw PdfUserError("En az bir görsel seç.")
        PDDocument(mem()).use { doc ->
            uris.forEachIndexed { i, uri ->
                onProgress(i + 1, uris.size)
                val bmp = decodeImage(ctx, uri, 2480) ?: throw PdfUserError("Görsel okunamadı: ${displayName(ctx, uri)}")
                val landscape = bmp.width > bmp.height
                val box = if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                val page = PDPage(box)
                doc.addPage(page)
                val img = JPEGFactory.createFromImage(doc, bmp, 0.85f)
                val margin = 18f
                val scale = min((box.width - 2 * margin) / bmp.width, (box.height - 2 * margin) / bmp.height)
                val w = bmp.width * scale; val h = bmp.height * scale
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, (box.width - w) / 2, (box.height - h) / 2, w, h) }
                bmp.recycle()
            }
            val stamp = SimpleDateFormat("ddMMyyyy_HHmm", Locale.ROOT).format(Date())
            val name = if (uris.size == 1) FileNamer.derived(displayName(ctx, uris.first()), "pdf", "pdf") else "Gorseller_$stamp.pdf"
            val saved = saveDoc(ctx, "gorsel_pdf", doc, name)
            return PdfResult(listOf(saved), "${uris.size} görsel, ${uris.size} sayfalık PDF oldu (${mb(saved.size)}).")
        }
    }

    fun decodeImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        val scaled = scaleDown(bmp, maxSide)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    fun mb(bytes: Long): String = if (bytes >= 1024 * 1024) String.format(Locale("tr"), "%.1f MB", bytes / 1048576.0)
        else "${(bytes / 1024).coerceAtLeast(1)} KB"
}
