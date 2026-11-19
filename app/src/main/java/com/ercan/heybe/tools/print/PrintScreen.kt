package com.ercan.heybe.tools.print

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ercan.heybe.core.PrintFit
import com.ercan.heybe.tools.pdf.FileList
import com.ercan.heybe.tools.pdf.keepAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Her ekranda "Yazdır" düğmesi için: dosyaları hazırlar ve sistemin yazdırma penceresini açar. */
@Composable
fun rememberPrint(): (List<Uri>) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(ctx) {
        val f: (List<Uri>) -> Unit = { uris ->
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Printer.prepare(ctx, uris) } }
                    .onSuccess { open -> runCatching { open() }.onFailure { Toast.makeText(ctx, "Yazdırma açılamadı: ${it.message}", Toast.LENGTH_LONG).show() } }
                    .onFailure { Toast.makeText(ctx, (it as? PrintError)?.message ?: "Yazdırılamadı: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        f
    }
}

@Composable
fun PrintScreen(initial: List<Uri>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(initial) }
    var rotate by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { got ->
        keepAccess(ctx, got); files = files + got
    }
    val mimes = remember(files) { files.map { Printer.mimeOf(ctx, it) } }
    val problem = if (files.isEmpty()) null else PrintFit.problem(mimes)
    val images = files.isNotEmpty() && mimes.all(PrintFit::isImage)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("PDF ya da fotoğraf seç; Android'in yazdırma penceresi açılır. Orada yazıcıyı, kopya sayısını, sayfaları, renkli/siyah-beyaz ve kâğıt boyutunu seçebilirsin. Yazıcı yerine \"PDF olarak kaydet\" de seçilebilir.",
            style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.padding(top = 12.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf", "image/*")) }) { Text("Dosya seç") }
        }
        if (files.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp)) { FileList(files, { files = it }) }
        }
        if (images) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Yatay fotoğrafları döndür", style = MaterialTheme.typography.bodyLarge)
                    Text("Sayfayı daha iyi doldurur. Her fotoğraf ayrı sayfaya basılır.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(rotate, { rotate = it })
            }
        }
        if (files.size > 1 && !images && problem == null) {
            Text("PDF'ler sırayla tek iş olarak yazdırılır.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Button(
            enabled = files.isNotEmpty() && problem == null && !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
            onClick = {
                scope.launch {
                    busy = true
                    runCatching { withContext(Dispatchers.IO) { Printer.prepare(ctx, files, rotate) } }
                        .onSuccess { open -> runCatching { open() }.onFailure { Toast.makeText(ctx, "Yazdırma açılamadı: ${it.message}", Toast.LENGTH_LONG).show() } }
                        .onFailure { Toast.makeText(ctx, (it as? PrintError)?.message ?: "Yazdırılamadı: ${it.message}", Toast.LENGTH_LONG).show() }
                    busy = false
                }
            },
        ) { Text(if (files.isEmpty()) "Yazdır" else "Yazdır (${files.size} dosya)") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        Card(Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Yazıcı listede görünmüyorsa", style = MaterialTheme.typography.titleSmall)
                Text("• Wi-Fi yazıcılarda telefon ve yazıcı aynı ağda olmalı.\n" +
                    "• USB kabloyla (OTG) bağlı yazıcılar, bir yazdırma eklentisi (ör. Canon Print Service ya da NokoPrint) yüklüyse listede çıkar.\n" +
                    "• Ayarlar → Bağlantı → Yazdırma'da \"Varsayılan yazdırma hizmeti\" açık olmalı.\n" +
                    "• Canon yazıcılar için Play Store'daki \"Canon Print Service\" eklentisi daha fazla ayar sunar.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                        .onFailure { Toast.makeText(ctx, "Yazdırma ayarları açılamadı.", Toast.LENGTH_SHORT).show() }
                }) { Text("Yazdırma ayarlarını aç") }
            }
        }
    }
}
