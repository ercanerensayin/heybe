package com.ercan.heybe.tools.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ercan.heybe.core.FileNamer
import com.ercan.heybe.core.TextFinds
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.tools.pdf.PdfEngine
import com.ercan.heybe.ui.rememberWithPermissions
import com.ercan.heybe.ui.storagePermissions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Telefonun içinde çalışan metin tanıma (ML Kit, Latin alfabesi: Türkçe dahil). */
object Ocr {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(ctx: Context, uri: Uri): String {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(ctx, uri) }
        return suspendCancellableCoroutine { cont ->
            client.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Heybe", text))
    Toast.makeText(ctx, "Kopyalandı.", Toast.LENGTH_SHORT).show()
}

@Composable
fun OcrScreen(initial: List<Uri>, onTranslate: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")

    fun run(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            error = null
            val parts = mutableListOf<String>()
            uris.forEachIndexed { i, u ->
                busy = if (uris.size > 1) "Görsel ${i + 1} / ${uris.size} okunuyor…" else "Metin okunuyor…"
                runCatching { Ocr.read(ctx, u) }
                    .onSuccess { if (it.isNotBlank()) parts += it }
                    .onFailure { e -> error = "Okunamadı: ${e.message}. (İlk kullanımda Google Play Hizmetleri tanıma modülünü indirir; internet bağlantısı gerekebilir.)" }
            }
            busy = null
            source = PdfEngine.displayName(ctx, uris.first())
            text = parts.joinToString("\n\n— — —\n\n")
            if (parts.isEmpty() && error == null) error = "Görselde metin bulunamadı."
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { run(listOf(it)) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { run(it) }
    LaunchedEffect(Unit) { if (initial.isNotEmpty() && text.isEmpty()) run(initial) }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Fotoğraftaki ya da ekran görüntüsündeki yazıyı metne çevirir. İnternete gönderilmez, telefonda işlenir.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = {
                val dir = File(ctx.cacheDir, "ocr").apply { mkdirs() }
                val f = File(dir, "cekim_${System.currentTimeMillis()}.jpg")
                val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                cameraUri = u
                runCatching { takePhoto.launch(u) }.onFailure { Toast.makeText(ctx, "Kamera açılamadı.", Toast.LENGTH_LONG).show() }
            }) { Text("Fotoğraf çek") }
            OutlinedButton(onClick = { pick.launch("image/*") }) { Text("Görsel seç") }
        }
        busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

        if (text.isNotBlank()) {
            val finds = remember(text) { TextFinds.all(text) }
            if (finds.isNotEmpty()) {
                Text("Bulunanlar (dokununca kopyalanır)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                finds.forEach { f ->
                    AssistChip(onClick = { copy(ctx, if (f.kind == TextFinds.Kind.IBAN) f.value.replace(" ", "") else f.value) },
                        label = { Text("${f.kind.label}: ${f.value}", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Metin (düzenlenebilir)") }, minLines = 8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { copy(ctx, text) }) { Text("Kopyala") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Paylaş"))
                }) { Text("Paylaş") }
                OutlinedButton(onClick = { onTranslate(text) }) { Text("Çevir") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = {
                    withStorage {
                        scope.launch {
                            val r = runCatching {
                                withContext(Dispatchers.IO) {
                                    val stamp = SimpleDateFormat("ddMMyyyy_HHmm", Locale.ROOT).format(Date())
                                    val name = source?.let { FileNamer.derived(it, "metin", "txt") } ?: "Metin_$stamp.txt"
                                    val saved = FileStore.save(ctx, FileStore.Folder.METIN, name, "text/plain") { it.write(text.toByteArray()) }
                                    Recorder.done(ctx, "ocr", saved, "text/plain"); saved
                                }
                            }
                            r.onSuccess { Toast.makeText(ctx, "Kaydedildi: ${FileStore.locationLabel(FileStore.Folder.METIN)}/${it.name}", Toast.LENGTH_LONG).show() }
                                .onFailure { Toast.makeText(ctx, "Kaydedilemedi: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }) { Text("TXT kaydet") }
            }
        }
    }
}
