package com.ercan.heybe.tools.scan

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ercan.heybe.core.FileNamer
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.share.SharedInbox
import com.ercan.heybe.tools.convert.ConvertWorker
import com.ercan.heybe.tools.convert.Target
import com.ercan.heybe.tools.pdf.PdfEngine
import com.ercan.heybe.tools.pdf.openFile
import com.ercan.heybe.tools.pdf.shareFile
import com.ercan.heybe.ui.rememberWithPermissions
import com.ercan.heybe.ui.storagePermissions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun ScanScreen(openTool: (String) -> Unit, onConvertStarted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<FileStore.Saved?>(null) }
    var busy by remember { mutableStateOf(false) }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data) ?: return@rememberLauncherForActivityResult
        pages = r.pages?.map { it.imageUri }.orEmpty()
        pdfUri = r.pdf?.uri
        saved = null
        name = "Tarama_" + SimpleDateFormat("dd.MM.yyyy_HHmm", Locale.ROOT).format(Date())
    }

    fun startScan() {
        val act = ctx.activity() ?: return
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                Toast.makeText(ctx, "Tarayıcı açılamadı: ${it.message}. (Google Play Hizmetleri gerekir; ilk açılışta modül indirilir.)", Toast.LENGTH_LONG).show()
            }
    }

    /** PDF'i kaydeder (bir kez); kaydedileni döndürür. */
    suspend fun ensureSaved(): FileStore.Saved? {
        saved?.let { return it }
        val src = pdfUri ?: return null
        return withContext(Dispatchers.IO) {
            val fileName = FileNamer.clean(name.ifBlank { "Tarama" }) + ".pdf"
            val s = FileStore.save(ctx, FileStore.Folder.PDF, fileName, "application/pdf") { os ->
                ctx.contentResolver.openInputStream(src)!!.use { it.copyTo(os) }
            }
            Recorder.done(ctx, "tarayici", s, "application/pdf"); s
        }.also { saved = it }
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Kâğıdın kenarları otomatik bulunur, perspektif düzeltilir; gölge ve leke temizleme filtreleri tarama ekranında. Birden çok sayfa taranabilir, galeriden fotoğraf da eklenebilir.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { startScan() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) {
            Text(if (pages.isEmpty()) "Taramaya başla" else "Yeni tarama")
        }

        if (pages.isNotEmpty()) {
            Text("${pages.size} sayfa", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(pages) { i, u ->
                    val bmp by produceState<Bitmap?>(null, u) { value = withContext(Dispatchers.IO) { PdfEngine.decodeImage(ctx, u, 400) } }
                    Card { bmp?.let { Image(it.asImageBitmap(), "Sayfa ${i + 1}", Modifier.width(110.dp).height(150.dp), contentScale = ContentScale.Crop) } }
                }
            }
            OutlinedTextField(name, { name = it; saved = null }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Dosya adı") }, singleLine = true)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(enabled = !busy, onClick = {
                    withStorage { scope.launch { busy = true; runCatching { ensureSaved() }
                        .onSuccess { s -> s?.let { Toast.makeText(ctx, "Kaydedildi: ${FileStore.locationLabel(FileStore.Folder.PDF)}/${it.name}", Toast.LENGTH_LONG).show() } }
                        .onFailure { Toast.makeText(ctx, "Kaydedilemedi: ${it.message}", Toast.LENGTH_LONG).show() }; busy = false } }
                }) { Text(if (saved == null) "PDF kaydet" else "Kaydedildi ✓") }
                saved?.let { s ->
                    OutlinedButton(onClick = { openFile(ctx, s.uri) }) { Text("Aç") }
                    OutlinedButton(onClick = { shareFile(ctx, s.uri) }) { Text("Paylaş") }
                }
            }
            Text("Devam et", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { SharedInbox.put(null, pages); openTool("ocr") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Metne çevir (OCR)")
                }
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    withStorage {
                        scope.launch {
                            busy = true
                            runCatching {
                                val s = ensureSaved() ?: error("PDF yok")
                                val f = withContext(Dispatchers.IO) {
                                    val dir = File(ctx.cacheDir, "donustur/${System.currentTimeMillis()}").apply { mkdirs() }
                                    File(dir, s.name).also { out -> ctx.contentResolver.openInputStream(s.uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }
                                }
                                ConvertWorker.enqueue(ctx, listOf(Triple(f, s.name, "application/pdf")), Target.DOCX)
                            }.onSuccess { Toast.makeText(ctx, "Word'e çevriliyor; ilerleme İndirilenler'de.", Toast.LENGTH_SHORT).show(); onConvertStarted() }
                                .onFailure { Toast.makeText(ctx, "Başlatılamadı: ${it.message}", Toast.LENGTH_LONG).show() }
                            busy = false
                        }
                    }
                }) { Text("Word'e çevir (OCR ile)") }
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    withStorage {
                        scope.launch {
                            busy = true
                            val r = runCatching {
                                withContext(Dispatchers.IO) {
                                    pages.mapIndexed { i, u ->
                                        val s = FileStore.save(ctx, FileStore.Folder.GORSEL, FileNamer.clean(name.ifBlank { "Tarama" }) + "_s${i + 1}.jpg", "image/jpeg") { os ->
                                            ctx.contentResolver.openInputStream(u)!!.use { it.copyTo(os) }
                                        }
                                        Recorder.done(ctx, "tarayici", s, "image/jpeg"); s
                                    }
                                }
                            }
                            r.onSuccess { Toast.makeText(ctx, "${it.size} sayfa JPG olarak kaydedildi (${FileStore.locationLabel(FileStore.Folder.GORSEL)}).", Toast.LENGTH_LONG).show() }
                                .onFailure { Toast.makeText(ctx, "Kaydedilemedi: ${it.message}", Toast.LENGTH_LONG).show() }
                            busy = false
                        }
                    }
                }) { Text("Sayfaları JPG olarak kaydet") }
            }
        }
    }
}
