package com.ercan.heybe.tools.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ercan.heybe.core.QrText
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.tools.pdf.shareFile
import com.ercan.heybe.ui.rememberWithPermissions
import com.ercan.heybe.ui.storagePermissions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QrScreen() {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Oku") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Oluştur") })
        }
        if (tab == 0) ReadTab() else CreateTab()
    }
}

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Kopyalandı.", Toast.LENGTH_SHORT).show()
}

@Composable
private fun ReadTab() {
    val ctx = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        info = "Görsel taranıyor…"
        runCatching {
            BarcodeScanning.getClient().process(InputImage.fromFilePath(ctx, uri))
                .addOnSuccessListener { list ->
                    val v = list.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                    if (v == null) info = "Görselde QR ya da barkod bulunamadı." else { result = v; info = null }
                }
                .addOnFailureListener { info = "Okunamadı: ${it.message}" }
        }.onFailure { info = "Görsel açılamadı: ${it.message}" }
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Button(onClick = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).enableAutoZoom().build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { b -> result = b.rawValue ?: b.displayValue; info = null }
                .addOnFailureListener { info = "Tarayıcı açılamadı: ${it.message}. (Google Play Hizmetleri gerekir; ilk açılışta tarayıcı modülü indirilir.)" }
        }, modifier = Modifier.fillMaxWidth()) { Text("Kamerayla tara") }
        OutlinedButton(onClick = { pickImage.launch("image/*") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Görselden oku (ekran görüntüsü vb.)")
        }
        info?.let { Text(it, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        result?.let { raw -> ResultCard(raw) }
    }
}

@Composable
private fun ResultCard(raw: String) {
    val ctx = LocalContext.current
    val wifi = QrText.parseWifi(raw)
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            when {
                wifi != null -> {
                    Text("Wi-Fi ağı", style = MaterialTheme.typography.titleMedium)
                    Text("Ağ adı: ${wifi.ssid}")
                    Text("Güvenlik: ${wifi.security.label}")
                    if (wifi.password.isNotEmpty()) SelectionContainer { Text("Şifre: ${wifi.password}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (wifi.password.isNotEmpty()) OutlinedButton(onClick = { copy(ctx, "Wi-Fi şifresi", wifi.password) }) { Text("Şifreyi kopyala") }
                    }
                }
                else -> {
                    Text(if (QrText.isUrl(raw)) "Bağlantı" else "İçerik", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(raw, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (QrText.isUrl(raw)) OutlinedButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw.trim()))) }
                        }) { Text("Aç") }
                        OutlinedButton(onClick = { copy(ctx, "QR", raw) }) { Text("Kopyala") }
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, raw), "Paylaş"))
                        }) { Text("Paylaş") }
                    }
                }
            }
        }
    }
}

fun qrBitmap(content: String, size: Int = 768): Bitmap {
    val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

@Composable
private fun CreateTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var wifiMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf(QrText.Security.WPA) }
    var hidden by remember { mutableStateOf(false) }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")

    val content = if (wifiMode) (if (ssid.isBlank()) "" else QrText.wifi(QrText.Wifi(ssid, pass, sec, hidden))) else text
    val bmp = remember(content) { if (content.isBlank()) null else runCatching { qrBitmap(content) }.getOrNull() }

    fun save(thenShare: Boolean) = withStorage {
        val b = bmp ?: return@withStorage
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val stamp = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.ROOT).format(Date())
                    val name = (if (wifiMode) "QR_WiFi_" else "QR_") + stamp + ".png"
                    val saved = FileStore.save(ctx, FileStore.Folder.GORSEL, name, "image/png") { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Recorder.done(ctx, "qr", saved, "image/png")
                    saved
                }
            }
            r.onSuccess {
                if (thenShare) shareFile(ctx, it.uri, "image/png")
                else Toast.makeText(ctx, "Kaydedildi: ${FileStore.locationLabel(FileStore.Folder.GORSEL)}/${it.name}", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(ctx, "Kaydedilemedi: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !wifiMode, onClick = { wifiMode = false }); Text("Metin / bağlantı", Modifier.clickable { wifiMode = false })
            Spacer(Modifier.size(12.dp))
            RadioButton(selected = wifiMode, onClick = { wifiMode = true }); Text("Wi-Fi", Modifier.clickable { wifiMode = true })
        }
        if (!wifiMode) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Metin ya da bağlantı") }, minLines = 2)
        } else {
            OutlinedTextField(ssid, { ssid = it }, Modifier.fillMaxWidth(), label = { Text("Ağ adı (SSID)") }, singleLine = true)
            if (sec != QrText.Security.NONE)
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Şifre") }, singleLine = true)
            QrText.Security.entries.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { sec = s }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sec == s, onClick = { sec = s }); Text(s.label)
                }
            }
            Row(Modifier.clickable { hidden = !hidden }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(hidden, { hidden = it }); Text("Gizli ağ")
            }
        }
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = "QR kod", modifier = Modifier.padding(top = 12.dp).size(260.dp).align(Alignment.CenterHorizontally))
            Text("Başka bir telefonla tarayarak deneyebilirsin.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save(false) }, enabled = bmp != null) { Text("Kaydet") }
            OutlinedButton(onClick = { save(true) }, enabled = bmp != null) { Text("Paylaş") }
        }
    }
}
