package com.ercan.heybe.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
        var label by remember { mutableStateOf(FileStore.locationLabel()) }
        val pickTree = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    FileStore.setTree(ctx, uri)
                }.onSuccess { label = FileStore.locationLabel(); Toast.makeText(ctx, "Yeni dosyalar buraya kaydedilecek: $label", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(ctx, "Klasör seçilemedi: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        Text("Şu an: $label. Araç başına alt klasörler (Video, PDF, Görsel, Ses, Belgeler…) otomatik oluşturulur. SD kart da seçilebilir.",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickTree.launch(null) }) { Text("Klasör seç") }
            if (FileStore.customLabel != null) androidx.compose.material3.TextButton(onClick = {
                FileStore.setTree(ctx, null); label = FileStore.locationLabel()
            }) { Text("Varsayılana dön") }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Altyazı (OpenSubtitles)", style = MaterialTheme.typography.titleMedium)
        Text("Perde'de \"İnternetten ara\" için opensubtitles.com'da ücretsiz hesap aç, Profil → API consumers bölümünden bir API anahtarı oluştur ve buraya yapıştır. Kullanıcı adı ve şifre isteğe bağlıdır; girersen günlük indirme hakkın artar. Şifre yalnızca bu telefonda saklanır, yedeğe girmez.",
            style = MaterialTheme.typography.bodySmall)
        var osApi by remember { mutableStateOf(com.ercan.heybe.tools.perde.OpenSubs.apiKey(ctx)) }
        var osUser by remember { mutableStateOf(com.ercan.heybe.tools.perde.OpenSubs.user(ctx)) }
        var osPass by remember { mutableStateOf("") }
        androidx.compose.material3.OutlinedTextField(osApi, { osApi = it }, Modifier.fillMaxWidth(), label = { Text("API anahtarı") }, singleLine = true)
        androidx.compose.material3.OutlinedTextField(osUser, { osUser = it }, Modifier.fillMaxWidth(), label = { Text("Kullanıcı adı (isteğe bağlı)") }, singleLine = true)
        androidx.compose.material3.OutlinedTextField(osPass, { osPass = it }, Modifier.fillMaxWidth(),
            label = { Text(if (com.ercan.heybe.tools.perde.OpenSubs.hasPassword(ctx)) "Şifre (kayıtlı; değiştirmek için yaz)" else "Şifre (isteğe bağlı)") },
            singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        OutlinedButton(onClick = {
            com.ercan.heybe.tools.perde.OpenSubs.save(ctx, osApi, osUser, osPass.ifEmpty { null }); osPass = ""
            Toast.makeText(ctx, "Kaydedildi.", Toast.LENGTH_SHORT).show()
        }) { Text("Kaydet") }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Yedekleme", style = MaterialTheme.typography.titleMedium)
        Text("Favoriler, son kullanılanlar, siteler, Perde'nin kaldığın yer ve altyazı ayarları ile İndirilenler geçmişi tek bir JSON dosyasına yazılır. Dosyalar (videolar, PDF'ler) yedeğe girmez.",
            style = MaterialTheme.typography.bodySmall)
        val restore = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                val m = runCatching { com.ercan.heybe.data.Backup.import(ctx, uri) }.getOrElse { "Geri yüklenemedi: ${it.message}" }
                Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
            }
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                withStorage {
                    scope.launch {
                        val m = runCatching { com.ercan.heybe.data.Backup.export(ctx) }
                            .fold({ "Yedeklendi: ${FileStore.locationLabel(FileStore.Folder.YEDEK)}/${it.name}" }, { "Yedeklenemedi: ${it.message}" })
                        Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("Yedekle") }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "*/*")) }) { Text("Geri yükle") }
        }

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
        Text("Paylaş menüsü", style = MaterialTheme.typography.titleMedium)
        Text("Paylaş listesinde \"Heybe\" dosyayı uygun araca yönlendirir. Ayrıca iki sabit kısayol vardır: \"Heybe: Video İndir\" (linki en yüksek kalitede hemen indirir) ve aşağıda seçtiğin ikinci kısayol.",
            style = MaterialTheme.typography.bodyMedium)
        var second by remember { mutableStateOf(com.ercan.heybe.share.Shortcuts.current(ctx)) }
        com.ercan.heybe.core.ShareRouter.Shortcut.second.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable {
                com.ercan.heybe.share.Shortcuts.setSecond(ctx, s); second = s
            }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = second == s, onClick = { com.ercan.heybe.share.Shortcuts.setSecond(ctx, s); second = s })
                Text("Heybe: " + s.label)
            }
        }
        Text("Değişikliğin paylaş menüsünde görünmesi birkaç saniye sürebilir.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Uygulama güncellemeleri", style = MaterialTheme.typography.titleMedium)
        Text("Heybe günde bir yeni sürüm olup olmadığına bakar ve varsa bildirim gönderir. Kurulum uygulamanın içinden yapılır.",
            style = MaterialTheme.typography.bodyMedium)
        var updateMsg by remember { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                updateMsg = "Denetleniyor…"
                scope.launch {
                    updateMsg = when (val r = com.ercan.heybe.update.Updater.check(ctx)) {
                        is com.ercan.heybe.update.Updater.Check.NewVersion -> { com.ercan.heybe.update.Updater.pending = r.available; "Yeni sürüm bulundu: ${r.available.info.versionName}" }
                        com.ercan.heybe.update.Updater.Check.UpToDate -> "En güncel sürümü kullanıyorsun."
                        is com.ercan.heybe.update.Updater.Check.Failed -> r.message
                    }
                }
            }) { Text("Güncellemeleri denetle") }
            androidx.compose.material3.TextButton(onClick = { com.ercan.heybe.update.Updater.openReleasesPage(ctx) }) { Text("Sürümler sayfası") }
        }
        updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Heybe $version", style = MaterialTheme.typography.bodySmall)
    }
}
