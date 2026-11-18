package com.ercan.heybe.tools.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ercan.heybe.core.FileNamer
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.tools.pdf.FileList
import com.ercan.heybe.tools.pdf.PdfEngine
import com.ercan.heybe.tools.pdf.PdfResult
import com.ercan.heybe.tools.pdf.PdfUserError
import com.ercan.heybe.tools.pdf.RunStatus
import com.ercan.heybe.tools.pdf.ToolRun
import com.ercan.heybe.tools.pdf.keepAccess
import com.ercan.heybe.ui.rememberWithPermissions
import com.ercan.heybe.ui.storagePermissions

enum class ImgQuality(val label: String, val q: Int) { YUKSEK("Yüksek kalite (%85)", 85), ORTA("Dengeli (%70, önerilen)", 70), KUCUK("En küçük (%50)", 50) }
enum class ImgSize(val label: String, val side: Int) { ORIJINAL("Orijinal boyut", 4096), B2560("En uzun kenar 2560 px", 2560), B1920("En uzun kenar 1920 px", 1920), B1280("En uzun kenar 1280 px", 1280) }

object ImageCompressor {
    private fun size(ctx: Context, uri: Uri): Long =
        runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L

    suspend fun run(ctx: Context, uris: List<Uri>, q: ImgQuality, s: ImgSize, report: (String) -> Unit): PdfResult {
        if (uris.isEmpty()) throw PdfUserError("En az bir görsel seç.")
        var before = 0L; var after = 0L
        val files = uris.mapIndexed { i, uri ->
            report("Görsel ${i + 1} / ${uris.size}")
            val bmp = PdfEngine.decodeImage(ctx, uri, s.side) ?: throw PdfUserError("Görsel okunamadı: ${PdfEngine.displayName(ctx, uri)}")
            val name = FileNamer.derived(PdfEngine.displayName(ctx, uri), "sikistirilmis", "jpg")
            val saved = try {
                FileStore.save(ctx, FileStore.Folder.GORSEL, name, "image/jpeg") { bmp.compress(Bitmap.CompressFormat.JPEG, q.q, it) }
            } finally { bmp.recycle() }
            Recorder.done(ctx, "gorsel_sikistir", saved, "image/jpeg")
            val b = size(ctx, uri); if (b > 0) before += b
            after += saved.size
            saved
        }
        val summary = if (before > 0) {
            val pct = 100 - (after * 100 / before)
            "${files.size} görsel: ${PdfEngine.mb(before)} → ${PdfEngine.mb(after)}" + if (pct > 0) " (%$pct küçüldü)." else " (küçülmedi; daha düşük kalite ya da boyut seç)."
        } else "${files.size} görsel sıkıştırıldı (${PdfEngine.mb(after)})."
        return PdfResult(files, summary, FileStore.Folder.GORSEL, "image/jpeg")
    }
}

@Composable
fun ImageCompressScreen(initial: List<Uri>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val run = remember { ToolRun() }
    var files by remember { mutableStateOf(initial) }
    var quality by remember { mutableStateOf(ImgQuality.ORTA) }
    var size by remember { mutableStateOf(ImgSize.B1920) }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { got -> keepAccess(ctx, got); files = files + got }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Fotoğraflar JPEG olarak yeniden kaydedilir; asıl dosyalara dokunulmaz.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        FileList(files, { files = it }, reorder = false)
        OutlinedButton(onClick = { pick.launch(arrayOf("image/*")) }, modifier = Modifier.padding(top = 8.dp)) { Text("Görsel ekle") }
        Text("Kalite", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        ImgQuality.entries.forEach { o ->
            Row(Modifier.fillMaxWidth().clickable { quality = o }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = quality == o, onClick = { quality = o }); Text(o.label)
            }
        }
        Text("Boyut", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        ImgSize.entries.forEach { o ->
            Row(Modifier.fillMaxWidth().clickable { size = o }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = size == o, onClick = { size = o }); Text(o.label)
            }
        }
        Button(
            onClick = { withStorage { run.start(scope) { report -> ImageCompressor.run(ctx, files, quality, size, report) } } },
            enabled = files.isNotEmpty() && !run.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Sıkıştır (${files.size} görsel)") }
        RunStatus(run)
    }
}
