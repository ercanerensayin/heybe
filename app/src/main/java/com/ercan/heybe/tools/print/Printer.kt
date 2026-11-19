package com.ercan.heybe.tools.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import com.ercan.heybe.core.PrintFit
import com.ercan.heybe.tools.pdf.PdfEngine
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream

class PrintError(message: String) : Exception(message)

/** Android'in kendi yazdırma sistemiyle PDF ve görsel yazdırır (Wi-Fi yazıcılar, "PDF olarak kaydet"). */
object Printer {

    fun mimeOf(ctx: Context, uri: Uri): String? = ctx.contentResolver.getType(uri)
        ?: uri.lastPathSegment?.lowercase()?.let {
            when {
                it.endsWith(".pdf") -> "application/pdf"
                it.endsWith(".jpg") || it.endsWith(".jpeg") -> "image/jpeg"
                it.endsWith(".png") -> "image/png"
                it.endsWith(".webp") -> "image/webp"
                it.endsWith(".heic") -> "image/heic"
                else -> null
            }
        }

    private fun jobName(ctx: Context, uris: List<Uri>): String {
        val first = runCatching { PdfEngine.displayName(ctx, uris.first()) }.getOrDefault("Heybe")
        return if (uris.size == 1) first else "$first (+${uris.size - 1})"
    }

    /**
     * Ağır kısmı (kopyalama, birleştirme) çağıran taraf IO iş parçacığında yapar;
     * dönen işlev ana iş parçacığında çağrılıp yazdırma penceresini açar.
     */
    fun prepare(ctx: Context, uris: List<Uri>, fitPage: Boolean = true): () -> Unit {
        val mimes = uris.map { mimeOf(ctx, it) }
        PrintFit.problem(mimes)?.let { throw PrintError(it) }
        val name = jobName(ctx, uris)
        val adapter: PrintDocumentAdapter = if (mimes.all(PrintFit::isPdf)) PdfAdapter(name, pdfFile(ctx, uris))
        else ImageAdapter(ctx.applicationContext, name, uris, fitPage)
        return {
            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val attrs = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
            pm.print(name, adapter, attrs)
        }
    }

    private fun pdfFile(ctx: Context, uris: List<Uri>): File {
        val dir = File(ctx.cacheDir, "yazdir").apply { deleteRecursively(); mkdirs() }
        val copies = uris.mapIndexed { i, u ->
            File(dir, "k$i.pdf").also { f ->
                (ctx.contentResolver.openInputStream(u) ?: throw PrintError("Dosya açılamadı."))
                    .use { inp -> f.outputStream().use { inp.copyTo(it) } }
            }
        }
        if (copies.size == 1) return copies.first()
        val out = File(dir, "birlesik.pdf")
        val docs = copies.map {
            runCatching { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
                .getOrElse { throw PrintError("PDF okunamadı (şifreli ya da bozuk olabilir).") }
        }
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { all ->
                val m = PDFMergerUtility()
                docs.forEach { m.appendDocument(all, it) }
                all.save(out)
            }
        } finally { docs.forEach { runCatching { it.close() } } }
        return out
    }

    private class PdfAdapter(val name: String, val file: File) : PrintDocumentAdapter() {
        private val pageCount: Int by lazy {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
            }.getOrDefault(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
        }
        private var laidOut = false

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, cb: PrintDocumentAdapter.LayoutResultCallback, extras: Bundle?) {
            if (cancel.isCanceled) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pageCount).build()
            cb.onLayoutFinished(info, !laidOut)
            laidOut = true
        }

        override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal, cb: PrintDocumentAdapter.WriteResultCallback) {
            Thread {
                runCatching {
                    file.inputStream().use { i -> FileOutputStream(dest.fileDescriptor).use { i.copyTo(it) } }
                }.onSuccess {
                    if (cancel.isCanceled) cb.onWriteCancelled() else cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }.onFailure { cb.onWriteFailed(it.message) }
            }.start()
        }
    }

    private class ImageAdapter(val ctx: Context, val name: String, val uris: List<Uri>, val fitPage: Boolean) : PrintDocumentAdapter() {
        @Volatile private var attrs: PrintAttributes? = null

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, cb: PrintDocumentAdapter.LayoutResultCallback, extras: Bundle?) {
            if (cancel.isCanceled) { cb.onLayoutCancelled(); return }
            val changed = old == null || old != new
            attrs = new
            val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO).setPageCount(uris.size).build()
            cb.onLayoutFinished(info, changed)
        }

        private fun wanted(i: Int, ranges: Array<out PageRange>) =
            ranges.any { it == PageRange.ALL_PAGES || (i >= it.start && i <= it.end) }

        override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal, cb: PrintDocumentAdapter.WriteResultCallback) {
            val a = attrs ?: run { cb.onWriteFailed("Sayfa ayarı yok"); return }
            Thread {
                val doc = PrintedPdfDocument(ctx, a)
                try {
                    for (i in uris.indices) {
                        if (cancel.isCanceled) { cb.onWriteCancelled(); return@Thread }
                        if (!wanted(i, pages)) continue
                        val page = doc.startPage(i)
                        val box = RectF(page.info.contentRect)
                        PdfEngine.decodeImage(ctx, uris[i], 2400)?.let { bmp -> draw(page.canvas, bmp, box) }
                        doc.finishPage(page)
                    }
                    FileOutputStream(dest.fileDescriptor).use { doc.writeTo(it) }
                    cb.onWriteFinished(arrayOf(*pages))
                } catch (e: Throwable) {
                    cb.onWriteFailed(e.message ?: "Yazdırma hazırlanamadı")
                } finally { doc.close() }
            }.start()
        }

        private fun draw(canvas: android.graphics.Canvas, src: Bitmap, box: RectF) {
            val p = PrintFit.place(src.width, src.height, box.left, box.top, box.width(), box.height(), autoRotate = fitPage)
            val bmp = if (p.rotate) Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(90f) }, true) else src
            canvas.drawBitmap(bmp, null, RectF(p.left, p.top, p.left + p.width, p.top + p.height),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            if (bmp !== src) bmp.recycle()
            src.recycle()
        }
    }
}
