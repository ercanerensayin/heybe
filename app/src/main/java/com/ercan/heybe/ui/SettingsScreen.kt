package com.ercan.heybe.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val withStorage = rememberWithPermissions(storagePermissions(), "Dosya kaydetmek için depolama izni gerekli.")
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Ayarlar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.padding(8.dp))

        Text("Kayıt yeri", style = MaterialTheme.typography.titleMedium)
        Text("Dosyalar araç başına alt klasörlere kaydedilir: " +
            FileStore.Folder.entries.filter { it != FileStore.Folder.DENEME }.joinToString(", ") { FileStore.locationLabel(it) } + ".",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(4.dp))
        Text("Kayıt sistemini sınamak için küçük bir deneme dosyası oluşturup İndirilenler'e ekler.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            withStorage {
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr")).format(Date())
                            val saved = FileStore.save(ctx, FileStore.Folder.DENEME, "heybe_deneme.txt", "text/plain") {
                                it.write("Heybe kayıt denemesi\n$stamp\n".toByteArray())
                            }
                            Recorder.done(ctx, "deneme", saved, "text/plain")
                            saved
                        }
                    }
                    result.onSuccess { Toast.makeText(ctx, "Kaydedildi: ${FileStore.locationLabel(FileStore.Folder.DENEME)}/${it.name}", Toast.LENGTH_LONG).show() }
                        .onFailure { Toast.makeText(ctx, "Kaydedilemedi: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }) { Text("Deneme dosyası kaydet") }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Video indirici (yt-dlp)", style = MaterialTheme.typography.titleMedium)
        Text("yt-dlp haftada bir kendiliğinden güncellenir; indirme başarısız olursa önce güncellenip bir kez daha denenir.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            Toast.makeText(ctx, "yt-dlp hazırlanıyor ve güncelleniyor…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val msg = runCatching {
                    com.ercan.heybe.tools.video.YtDlp.ensureReady(ctx)
                    val r = com.ercan.heybe.tools.video.YtDlp.updateNow(ctx)
                    r + (com.ercan.heybe.tools.video.YtDlp.version(ctx)?.let { " (sürüm $it)" } ?: "")
                }.getOrElse { "Güncellenemedi: ${it.message}" }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }) { Text("yt-dlp'yi şimdi güncelle") }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Paylaş menüsü ve uygulama güncellemeleri sonraki adımlarda buraya eklenecek.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Heybe $version", style = MaterialTheme.typography.bodySmall)
    }
}
