package com.ercan.heybe.tools.convert

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ercan.heybe.tools.pdf.FileList
import com.ercan.heybe.tools.pdf.PdfEngine
import com.ercan.heybe.ui.rememberWithPermissions
import com.ercan.heybe.ui.storagePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ConverterScreen(initial: List<Uri>, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    val perms = storagePermissions() + if (Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    val withPerms = rememberWithPermissions(perms, "İzin verilmedi; dönüştürme yine de çalışır ama bildirim görünmeyebilir.")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { got -> files = files + got }

    val info = remember(files) { files.map { u -> val n = PdfEngine.displayName(ctx, u); val m = ctx.contentResolver.getType(u); Triple(u, n, m) } }
    val kinds = info.map { ConvertEngine.kindOf(it.second, it.third) }
    val kind = kinds.firstOrNull()
    val mixed = kinds.distinct().size > 1
    val unsupported = info.zip(kinds).filter { it.second == null }.map { it.first.second }

    fun start(t: Target) = withPerms {
        busy = true
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "donustur/${System.currentTimeMillis()}").apply { mkdirs() }
                    info.mapIndexed { i, (u, n, m) ->
                        val f = File(dir, "${i}_" + n.replace('/', '_'))
                        ctx.contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: error("$n açılamadı")
                        Triple(f, n, m)
                    }
                }
            }
            r.onSuccess {
                ConvertWorker.enqueue(ctx, it, t)
                Toast.makeText(ctx, "${it.size} dosya sıraya alındı. İlerleme İndirilenler'de.", Toast.LENGTH_SHORT).show()
                files = emptyList(); onStarted()
            }.onFailure { Toast.makeText(ctx, "Dosya okunamadı: ${it.message}", Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Dosyaları seç; uygun hedefler aşağıda çıkar. Dönüştürme telefonda yapılır, dosyaların dışarı gönderilmez.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            pick.launch(arrayOf("application/pdf", "application/epub+zip", Target.DOCX.mime, "image/*", "video/*", "audio/*", Target.XLSX.mime, "text/csv", "text/comma-separated-values", "text/*", "application/octet-stream", "application/zip"))
        }, modifier = Modifier.padding(top = 8.dp)) { Text(if (files.isEmpty()) "Dosya seç" else "Dosya ekle") }
        FileList(files, { files = it }, reorder = false)

        when {
            files.isEmpty() -> Text("Desteklenenler: PDF, Word (DOCX), EPUB, görseller (HEIC, WEBP, PNG, JPG), videolar (MKV, AVI…), ses (WAV, FLAC, M4A…), Excel (XLSX), CSV, UYAP (UDF).\nEski .doc ve .xls dosyaları desteklenmiyor.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            unsupported.isNotEmpty() -> Text("Desteklenmeyen dosya: ${unsupported.joinToString()}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            mixed -> Text("Aynı türden dosyalar seç (ör. yalnızca PDF'ler ya da yalnızca görseller).", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            kind != null -> {
                Text("${kind.label} → hedef seç", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConvertEngine.targets(kind, info.first().second).forEach { t ->
                        Button(onClick = { start(t) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(t.label) }
                    }
                }
                if (kind == com.ercan.heybe.tools.convert.Kind.PDF) Text(
                    "EPUB ve Word'de satırlar paragraflara birleştirilir, sayfa numaraları ve tekrar eden üst bilgiler silinir, büyük punto satırlar başlık olur. Taranmış sayfalar OCR ile okunur. Tablolar düz metne döner.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (kind == com.ercan.heybe.tools.convert.Kind.DOCX || kind == com.ercan.heybe.tools.convert.Kind.EPUB) Text(
                    "PDF çıktısı sade bir düzenle hazırlanır: başlıklar, paragraflar ve resimler korunur; yazı tipleri ve sayfa düzeni basitleşir.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}
