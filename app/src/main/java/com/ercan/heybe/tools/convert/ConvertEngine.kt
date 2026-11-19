package com.ercan.heybe.tools.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.ercan.heybe.core.FileNamer
import com.ercan.heybe.core.doc.Block
import com.ercan.heybe.core.doc.Doc
import com.ercan.heybe.core.doc.DocReaders
import com.ercan.heybe.core.doc.DocxWriter
import com.ercan.heybe.core.doc.EpubWriter
import com.ercan.heybe.core.doc.Line
import com.ercan.heybe.core.doc.Page
import com.ercan.heybe.core.doc.PageItem
import com.ercan.heybe.core.doc.Reflow
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.tools.pdf.PdfEngine
import com.ercan.heybe.tools.pdf.PdfUserError
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Kind(val label: String) { PDF("PDF"), DOCX("Word"), EPUB("EPUB"), IMAGE("Görsel") }

enum class Target(val label: String, val ext: String, val mime: String, val folder: FileStore.Folder) {
    EPUB("EPUB (e-kitap)", "epub", "application/epub+zip", FileStore.Folder.BELGE),
    DOCX("Word (DOCX)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", FileStore.Folder.BELGE),
    TXT("Düz metin (TXT)", "txt", "text/plain", FileStore.Folder.BELGE),
    JPG_PAGES("Her sayfa JPG", "jpg", "image/jpeg", FileStore.Folder.GORSEL),
    PDF("PDF", "pdf", "application/pdf", FileStore.Folder.PDF),
    JPG("JPG", "jpg", "image/jpeg", FileStore.Folder.GORSEL),
    PNG("PNG", "png", "image/png", FileStore.Folder.GORSEL),
}

object ConvertEngine {
    private val imageExt = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")

    fun kindOf(name: String, mime: String?): Kind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || mime == "application/pdf" -> Kind.PDF
            ext == "docx" || mime == Target.DOCX.mime -> Kind.DOCX
            ext == "epub" || mime == "application/epub+zip" -> Kind.EPUB
            ext in imageExt || mime?.startsWith("image/") == true -> Kind.IMAGE
            else -> null
        }
    }

    fun targets(kind: Kind, name: String): List<Target> = when (kind) {
        Kind.PDF -> listOf(Target.EPUB, Target.DOCX, Target.TXT, Target.JPG_PAGES)
        Kind.DOCX -> listOf(Target.PDF, Target.EPUB, Target.TXT)
        Kind.EPUB -> listOf(Target.PDF, Target.DOCX, Target.TXT)
        Kind.IMAGE -> if (name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")) listOf(Target.PNG) else listOf(Target.JPG, Target.PNG)
    }

    /** Bir dosyayı dönüştürür, kaydeder; kaydedilen dosyaları döndürür. */
    fun convert(ctx: Context, input: File, name: String, mime: String?, target: Target, progress: (Int, String) -> Unit): List<FileStore.Saved> {
        val kind = kindOf(name, mime) ?: throw PdfUserError("Desteklenmeyen dosya: $name")
        val base = name.substringBeforeLast('.')
        fun save(fileName: String, write: (java.io.OutputStream) -> Unit) = FileStore.save(ctx, target.folder, fileName, target.mime, write)

        return when (target) {
            Target.JPG_PAGES -> pdfToJpgs(ctx, input, base, progress)
            Target.JPG, Target.PNG -> {
                progress(30, "Görsel çevriliyor")
                val bmp = PdfEngine.decodeImage(ctx, android.net.Uri.fromFile(input), 6000) ?: throw PdfUserError("Görsel okunamadı: $name")
                val out = try {
                    val flat = if (target == Target.JPG && bmp.hasAlpha()) Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                        val c = android.graphics.Canvas(it); c.drawColor(Color.WHITE); c.drawBitmap(bmp, 0f, 0f, null)
                    } else bmp
                    save(FileNamer.clean(base) + "." + target.ext) {
                        flat.compress(if (target == Target.JPG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it)
                    }
                } finally { bmp.recycle() }
                listOf(out)
            }
            else -> {
                val doc = when (kind) {
                    Kind.PDF -> pdfToDoc(ctx, input, base, progress)
                    Kind.DOCX -> { progress(20, "Word okunuyor"); DocReaders.docx(input, base, codec()) }
                    Kind.EPUB -> { progress(20, "EPUB okunuyor"); DocReaders.epub(input, base, codec()) }
                    Kind.IMAGE -> throw PdfUserError("Görseller yalnızca JPG/PNG'ye çevrilebilir.")
                }
                if (doc.blocks.none { it is Block.Para || it is Block.Heading })
                    throw PdfUserError("Belgede okunabilir metin bulunamadı.")
                progress(85, "${target.ext.uppercase()} yazılıyor")
                val fileName = FileNamer.clean(base) + "." + target.ext
                listOf(when (target) {
                    Target.EPUB -> save(fileName) { EpubWriter.write(doc, it) }
                    Target.DOCX -> save(fileName) { DocxWriter.write(doc, it) }
                    Target.TXT -> save(fileName) { it.write(toText(doc).toByteArray(Charsets.UTF_8)) }
                    Target.PDF -> save(fileName) { PdfLayout.write(ctx, doc, it) }
                    else -> error("hedef")
                })
            }
        }
    }

    fun toText(doc: Doc): String = doc.blocks.mapNotNull {
        when (it) { is Block.Heading -> "\n" + it.text.uppercase(java.util.Locale.forLanguageTag("tr")) + "\n"; is Block.Para -> it.text; else -> null }
    }.joinToString("\n\n").trim() + "\n"

    /** Ham görsel baytını JPEG'e çevirir (en uzun kenar 1600 px). */
    fun codec() = DocReaders.ImageCodec { bytes ->
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0) return@runCatching null
            var s = 1; while (max(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return@runCatching null
            toImage(b).also { b.recycle() }
        }.getOrNull()
    }

    private fun toImage(src: Bitmap): Block.Image {
        val side = max(src.width, src.height)
        val b = if (side > 1600) Bitmap.createScaledBitmap(src, (src.width * 1600f / side).roundToInt(), (src.height * 1600f / side).roundToInt(), true) else src
        val flat = if (b.hasAlpha()) Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also {
            val c = android.graphics.Canvas(it); c.drawColor(Color.WHITE); c.drawBitmap(b, 0f, 0f, null)
        } else b
        val bytes = ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        return Block.Image(bytes, flat.width, flat.height)
    }

    // ---------------- PDF okuma ----------------

    private class LineCollector : PDFTextStripper() {
        val pages = HashMap<Int, MutableList<Line>>()
        private val sb = StringBuilder()
        private val pos = ArrayList<TextPosition>()
        init { sortByPosition = true }
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) { sb.append(text); pos.addAll(textPositions) }
        override fun writeWordSeparator() { sb.append(' ') }
        override fun writeLineSeparator() { flush() }
        override fun writePageEnd() { flush(); super.writePageEnd() }
        private fun flush() {
            val t = sb.toString()
            if (t.isNotBlank() && pos.isNotEmpty()) {
                val sizes = pos.map { p -> p.fontSizeInPt.takeIf { it > 1.5f } ?: p.heightDir * 1.4f }.sorted()
                val size = sizes[sizes.size / 2]
                val top = pos.minOf { it.yDirAdj - it.heightDir }
                val bottom = pos.maxOf { it.yDirAdj }
                val left = pos.minOf { it.xDirAdj }
                val right = pos.maxOf { it.xDirAdj + it.widthDirAdj }
                pages.getOrPut(currentPageNo) { mutableListOf() } += Line(t, size, top, bottom, left, right)
            }
            sb.clear(); pos.clear()
        }
    }

    private fun load(input: File): PDDocument = try {
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly())
    } catch (e: InvalidPasswordException) { throw PdfUserError("Şifreli PDF desteklenmiyor.") }

    fun pdfToDoc(ctx: Context, input: File, title: String, progress: (Int, String) -> Unit): Doc {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        load(input).use { doc ->
            val n = doc.numberOfPages
            progress(5, "Metin okunuyor")
            val collector = LineCollector()
            collector.writeText(doc, StringWriter())
            val docTitle = doc.documentInformation?.title?.trim()?.takeIf { it.length in 2..150 } ?: title

            // Birden çok sayfada tekrar eden (logo vb.) görselleri ayıkla
            val imageCount = HashMap<Any, Int>()
            doc.pages.forEach { p -> val res = p.resources; res?.xObjectNames?.forEach { nm ->
                runCatching { res.getXObject(nm) as? PDImageXObject }.getOrNull()?.let { x -> imageCount[x.cosObject] = (imageCount[x.cosObject] ?: 0) + 1 }
            } }

            var ocr: com.google.mlkit.vision.text.TextRecognizer? = null
            var renderer: PdfRenderer? = null
            try {
                val pages = (0 until n).map { i ->
                    progress(10 + 70 * (i + 1) / n.coerceAtLeast(1), "Sayfa ${i + 1} / $n")
                    val page = doc.getPage(i)
                    val box = page.cropBox ?: PDRectangle.A4
                    val lines = collector.pages[i + 1].orEmpty().sortedWith(compareBy({ it.top }, { it.left }))
                    val items = mutableListOf<PageItem>()
                    if (lines.sumOf { it.text.length } < 25) {
                        // Taranmış sayfa: OCR
                        if (renderer == null) renderer = PdfRenderer(ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY))
                        if (ocr == null) ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        val text = runCatching { ocrPage(renderer!!, i, ocr!!) }.getOrDefault("")
                        if (text.isNotBlank()) items += PageItem.Ready(text)
                    } else {
                        lines.forEach { items += PageItem.Text(it) }
                        val res = page.resources
                        res?.xObjectNames?.forEach { nm ->
                            val x = runCatching { res.getXObject(nm) as? PDImageXObject }.getOrNull() ?: return@forEach
                            if (x.width < 120 || x.height < 120 || (imageCount[x.cosObject] ?: 0) >= 3) return@forEach
                            runCatching { x.image }.getOrNull()?.let { bmp -> items += PageItem.Img(toImage(bmp)); bmp.recycle() }
                        }
                    }
                    Page(items, box.width, box.height)
                }
                progress(82, "Paragraflar düzenleniyor")
                return Reflow.run(docTitle, pages)
            } finally {
                runCatching { renderer?.close() }
                runCatching { ocr?.close() }
            }
        }
    }

    private fun ocrPage(r: PdfRenderer, index: Int, client: com.google.mlkit.vision.text.TextRecognizer): String {
        r.openPage(index).use { p ->
            val scale = 2000f / max(p.width, p.height)
            val bmp = Bitmap.createBitmap((p.width * scale).roundToInt(), (p.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val result = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)))
            bmp.recycle()
            return result.textBlocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }
        }
    }

    private fun pdfToJpgs(ctx: Context, input: File, base: String, progress: (Int, String) -> Unit): List<FileStore.Saved> {
        PdfRenderer(ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            if (r.pageCount > 300) throw PdfUserError("Belge ${r.pageCount} sayfa; en fazla 300 sayfa resme çevrilebilir.")
            return (0 until r.pageCount).map { i ->
                progress(100 * (i + 1) / r.pageCount, "Sayfa ${i + 1} / ${r.pageCount}")
                r.openPage(i).use { p ->
                    val scale = 1600f / max(p.width, p.height)
                    val bmp = Bitmap.createBitmap((p.width * scale).roundToInt(), (p.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val saved = FileStore.save(ctx, FileStore.Folder.GORSEL, "${FileNamer.clean(base)}_s${i + 1}.jpg", "image/jpeg") {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
                    }
                    bmp.recycle(); saved
                }
            }
        }
    }
}

/** Belge modelini sade, okunaklı bir A4 PDF'e dizer (Türkçe karakterli gömülü yazı tipiyle). */
object PdfLayout {
    private const val MARGIN = 56f
    private val fontPaths = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf", "/system/fonts/DroidSans.ttf")

    private fun font(ctx: Context, doc: PDDocument): PDFont {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        runCatching {
            PDFBoxResourceLoader.getStream("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")?.use { return PDType0Font.load(doc, it) }
        }
        for (p in fontPaths) runCatching { val f = File(p); if (f.exists()) return PDType0Font.load(doc, f) }
        throw PdfUserError("Türkçe karakterleri destekleyen bir yazı tipi bulunamadı.")
    }

    fun write(ctx: Context, d: Doc, out: java.io.OutputStream) {
        PDDocument().use { pdf ->
            val font = font(ctx, pdf)
            val ok = HashMap<Char, Boolean>()
            fun safe(s: String) = buildString { for (c in s) append(if (ok.getOrPut(c) { runCatching { font.encode(c.toString()); true }.getOrDefault(false) }) c else '?') }
            val box = PDRectangle.A4
            val width = box.width - 2 * MARGIN
            var page: PDPage? = null
            var cs: PDPageContentStream? = null
            var y = 0f
            fun newPage() {
                cs?.close()
                page = PDPage(box).also { pdf.addPage(it) }
                cs = PDPageContentStream(pdf, page)
                y = box.height - MARGIN
            }
            fun ensure(h: Float) { if (cs == null || y - h < MARGIN) newPage() }
            fun text(t: String, size: Float, gapBefore: Float, gapAfter: Float) {
                val words = safe(t).split(' ').filter { it.isNotEmpty() }
                val lh = size * 1.4f
                y -= gapBefore
                var line = StringBuilder()
                fun emit() {
                    if (line.isEmpty()) return
                    ensure(lh); y -= lh
                    cs!!.beginText(); cs!!.setFont(font, size); cs!!.newLineAtOffset(MARGIN, y + size * 0.3f); cs!!.showText(line.toString()); cs!!.endText()
                    line = StringBuilder()
                }
                for (w in words) {
                    val cand = if (line.isEmpty()) w else "$line $w"
                    if (font.getStringWidth(cand) / 1000 * size > width && line.isNotEmpty()) { emit(); line.append(w) } else { line.setLength(0); line.append(cand) }
                }
                emit(); y -= gapAfter
            }
            for (b in d.blocks) when (b) {
                is Block.Heading -> { val s = when (b.level) { 1 -> 18f; 2 -> 14.5f; else -> 12.5f }; ensure(s * 3); text(b.text, s, s * 0.6f, s * 0.3f) }
                is Block.Para -> text(b.text, 11f, 0f, 6f)
                is Block.Image -> {
                    val img = runCatching { JPEGFactory.createFromStream(pdf, ByteArrayInputStream(b.jpeg)) }.getOrNull() ?: continue
                    val w = min(width, b.width * 0.75f); val h = w * b.height / b.width.coerceAtLeast(1)
                    val hh = min(h, box.height - 2 * MARGIN); val ww = hh * b.width / b.height.coerceAtLeast(1)
                    ensure(hh + 8); y -= hh + 4
                    cs!!.drawImage(img, MARGIN + (width - ww) / 2, y, ww, hh); y -= 8
                }
            }
            if (cs == null) newPage()
            cs?.close()
            pdf.save(out)
        }
    }
}
