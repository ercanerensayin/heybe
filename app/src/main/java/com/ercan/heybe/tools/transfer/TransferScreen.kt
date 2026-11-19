package com.ercan.heybe.tools.transfer

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.tools.qr.qrBitmap

@Composable
fun TransferScreen() {
    val ctx = LocalContext.current
    val view = LocalView.current
    var server by remember { mutableStateOf<TransferServer?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var extras by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { got ->
        extras = extras + got; server?.extras = extras
    }

    fun stop() { server?.stop(); server = null; url = null; view.keepScreenOn = false }
    fun start() {
        val s = TransferServer(ctx) { e -> main.post { log.add(0, e); if (log.size > 30) log.removeAt(log.lastIndex) } }
        runCatching { s.start() }.onFailure { Toast.makeText(ctx, "Sunucu başlatılamadı: ${it.message}", Toast.LENGTH_LONG).show(); return }
        s.extras = extras
        server = s; url = s.url(); view.keepScreenOn = true
        if (url == null) Toast.makeText(ctx, "Wi-Fi adresi bulunamadı. Wi-Fi'ye bağlan ya da telefonun erişim noktasını aç.", Toast.LENGTH_LONG).show()
    }
    DisposableEffect(Unit) { onDispose { stop() } }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Bilgisayarın tarayıcısından Heybe'nin dosyalarını indir ya da bilgisayardan telefona dosya gönder. Kablo ve program gerekmez; telefon ile bilgisayar aynı Wi-Fi'de olmalı.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (server == null) {
            Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)) { Text("Aktarımı başlat") }
        } else {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Bilgisayarın tarayıcısına bu adresi yaz:", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer { Text(url ?: "Adres bulunamadı (Wi-Fi bağlı değil)", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                    url?.let { u -> remember(u) { qrBitmap(u, 512) }.let { Image(it.asImageBitmap(), "Adres QR", Modifier.size(180.dp)) } }
                    Text("Başka bir telefon QR'ı okutarak da bağlanabilir. Bu ekran açık kaldığı sürece çalışır; ekran kendiliğinden kapanmaz.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Telefondan dosya ekle") }
                Button(onClick = { stop() }) { Text("Durdur") }
            }
            if (extras.isNotEmpty()) Text("${extras.size} ek dosya bilgisayardaki listede en üstte.", style = MaterialTheme.typography.bodySmall)
            Text("Gelenler: ${FileStore.locationLabel(FileStore.Folder.GELEN)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (log.isNotEmpty()) {
                Text("Son işlemler", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                log.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
