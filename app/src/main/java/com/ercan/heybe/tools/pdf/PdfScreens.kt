package com.ercan.heybe.tools.pdf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ercan.heybe.ui.rememberWithPermissions
import com.ercan.heybe.ui.storagePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PDF = "application/pdf"

/** PDF Birleştir / Böl: paylaşımla gelen dosyalar başlangıç listesidir. */
@Composable
fun PdfMergeSplitScreen(initial: List<Uri>) {
    var tab by remember { mutableStateOf(if (initial.size == 1) 1 else 0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Birleştir") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Böl") })
        }
        if (tab == 0) MergeTab(if (initial.size > 1) initial else emptyList())
        else SplitTab(initial.firstOrNull())
    }
}

@Composable
private fun MergeTab(initial: List<Uri>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val run = remember { ToolRun() }
    var files by remember { mutableStateOf(initial) }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { got ->
        keepAccess(ctx, got); files = files + got
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("PDF'leri listedeki sırayla tek dosyada birleştirir. Sırayı oklarla değiştirebilirsin.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        FileList(files, { files = it })
        OutlinedButton(onClick = { pick.launch(arrayOf(PDF)) }, modifier = Modifier.padding(top = 8.dp)) { Text("PDF ekle") }
        Button(
            onClick = { withStorage { run.start(scope) { PdfEngine.merge(ctx, files) } } },
            enabled = files.size >= 2 && !run.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Birleştir (${files.size} dosya)") }
        RunStatus(run)
    }
}

@Composable
private fun SplitTab(initial: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val run = remember { ToolRun() }
    var file by remember { mutableStateOf(initial) }
    var pages by remember { mutableStateOf<Int?>(null) }
    var each by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("") }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { got ->
        if (got != null) { keepAccess(ctx, listOf(got)); file = got }
    }
    LaunchedEffect(file) {
        pages = null
        file?.let { f -> pages = withContext(Dispatchers.IO) { runCatching { PdfEngine.pageCount(ctx, f) }.getOrNull() } }
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        if (file == null) Text("Bölmek için bir PDF seç.", style = MaterialTheme.typography.bodyMedium)
        else Text(PdfEngine.displayName(ctx, file!!) + (pages?.let { " — $it sayfa" } ?: ""), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { pick.launch(arrayOf(PDF)) }, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (file == null) "PDF seç" else "Başka PDF seç")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable { each = false }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !each, onClick = { each = false }); Text("Seçtiğim sayfaları ayrı bir PDF yap")
        }
        if (!each) {
            OutlinedTextField(value = range, onValueChange = { range = it }, singleLine = true,
                label = { Text("Sayfalar") }, placeholder = { Text("ör. 1-3, 5, 8-") },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp))
        }
        Row(Modifier.fillMaxWidth().clickable { each = true }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = each, onClick = { each = true }); Text("Her sayfayı ayrı dosya yap")
        }
        Button(
            onClick = {
                val f = file ?: return@Button
                withStorage {
                    run.start(scope) { report ->
                        if (each) PdfEngine.splitEach(ctx, f) { i, n -> report("Sayfa $i / $n") }
                        else PdfEngine.extract(ctx, f, range)
                    }
                }
            },
            enabled = file != null && !run.busy && (each || range.isNotBlank()),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Böl") }
        RunStatus(run)
    }
}

@Composable
fun PdfCompressScreen(initial: List<Uri>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val run = remember { ToolRun() }
    var file by remember { mutableStateOf(initial.firstOrNull()) }
    var level by remember { mutableStateOf(CompressLevel.ORTA) }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { got ->
        if (got != null) { keepAccess(ctx, listOf(got)); file = got }
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Metin seçilebilir kalır; yalnızca belgedeki fotoğraf ve taramalar yeniden sıkıştırılır.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        file?.let { Text(PdfEngine.displayName(ctx, it), style = MaterialTheme.typography.titleSmall) }
        OutlinedButton(onClick = { pick.launch(arrayOf(PDF)) }, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (file == null) "PDF seç" else "Başka PDF seç")
        }
        Spacer(Modifier.height(8.dp))
        CompressLevel.entries.forEach { l ->
            Row(Modifier.fillMaxWidth().clickable { level = l }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = level == l, onClick = { level = l }); Text(l.label)
            }
        }
        Button(
            onClick = {
                val f = file ?: return@Button
                withStorage { run.start(scope) { report -> PdfEngine.compress(ctx, f, level) { i, n -> report("Sayfa $i / $n") } } }
            },
            enabled = file != null && !run.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Sıkıştır") }
        RunStatus(run)
    }
}

@Composable
fun ImagesToPdfScreen(initial: List<Uri>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val run = remember { ToolRun() }
    var files by remember { mutableStateOf(initial) }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { got ->
        keepAccess(ctx, got); files = files + got
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Her görsel bir A4 sayfaya yerleştirilir; yatay fotoğraflar yatay sayfaya konur. Sırayı oklarla değiştirebilirsin.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        FileList(files, { files = it })
        OutlinedButton(onClick = { pick.launch(arrayOf("image/*")) }, modifier = Modifier.padding(top = 8.dp)) { Text("Görsel ekle") }
        Button(
            onClick = { withStorage { run.start(scope) { report -> PdfEngine.imagesToPdf(ctx, files) { i, n -> report("Görsel $i / $n") } } } },
            enabled = files.isNotEmpty() && !run.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("PDF yap (${files.size} görsel)") }
        RunStatus(run)
    }
}
