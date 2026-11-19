package com.ercan.heybe.tools.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ercan.heybe.core.NetMath
import com.ercan.heybe.core.WifiQr
import com.ercan.heybe.tools.qr.qrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NetworkScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf("Bağlantı", "Wi-Fi QR", "Cihazlar", "Hız testi").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) { 0 -> InfoTab(); 1 -> QrTab(); 2 -> DevicesTab(); else -> SpeedTab() }
    }
}

@Composable
private fun rememberInfo(): Pair<NetTools.Info?, () -> Unit> {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<NetTools.Info?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { info = withContext(Dispatchers.IO) { runCatching { NetTools.info(ctx) }.getOrNull() } }
    return info to { tick++ }
}

private fun openRouter(ctx: Context, gw: String) {
    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse("http://$gw")) }
        .onFailure { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$gw"))) } }
}

@Composable
private fun InfoTab() {
    val ctx = LocalContext.current
    val (info, refresh) = rememberInfo()
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        val i = info
        if (i == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(when { i.wifi -> "Wi-Fi'ye bağlı"; i.cellular -> "Mobil veriyle bağlı"; i.ip != null -> "Bağlı"; else -> "Bağlantı yok" },
                    style = MaterialTheme.typography.titleMedium)
                @Composable fun row(k: String, v: String?) { if (!v.isNullOrBlank()) Row(Modifier.padding(top = 6.dp)) {
                    Text(k, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, fontWeight = FontWeight.Medium) } }
                if (i.wifi) row("Ağ adı", i.ssid ?: "(Android gizliyor)")
                row("Telefonun IP'si", i.ip?.let { "$it/${i.prefix}" })
                row("Modem", i.gateway)
                row("DNS", i.dns.joinToString(", "))
                row("Bağlantı hızı", i.linkMbps?.let { "$it Mbps (Wi-Fi)" })
            }
        }
        if (i.wifi && i.gateway != null) {
            Button(onClick = { openRouter(ctx, i.gateway) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)) { Text("Modem arayüzünü aç") }
            Text("Modemin kullanıcı adı ve şifresi genelde cihazın altındaki etikette yazar (çoğu zaman admin / admin ya da etikette yazan şifre).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = refresh) { Text("Yenile") }
            OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }) { Text("Wi-Fi ayarları") }
        }
    }
}

@Composable
private fun QrTab() {
    val ctx = LocalContext.current
    val (info, _) = rememberInfo()
    val sp = remember { ctx.getSharedPreferences("ag", Context.MODE_PRIVATE) }
    var ssid by rememberSaveable { mutableStateOf(sp.getString("ssid", "") ?: "") }
    var pass by rememberSaveable { mutableStateOf("") }
    var sec by rememberSaveable { mutableStateOf(WifiQr.Security.WPA) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var show by rememberSaveable { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
    LaunchedEffect(info) { if (ssid.isBlank()) info?.ssid?.let { ssid = it } }
    val problem = WifiQr.problem(ssid, pass, sec)
    val content = WifiQr.build(ssid.trim(), pass, sec, hidden)
    val bmp = remember(content, show) { if (show && problem == null) qrBitmap(content, 900) else null }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        if (bmp != null) {
            Box(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), contentAlignment = Alignment.Center) {
                Image(bmp.asImageBitmap(), "Wi-Fi QR", Modifier.fillMaxWidth().aspectRatio(1f))
            }
            Text(ssid, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            Text("Misafirin telefon kamerasıyla okutması yeterli; şifre yazmadan bağlanır.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedButton(onClick = { show = false }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Düzenle") }
            return@Column
        }
        Text("Ev ağının QR kodunu oluştur. Şifre yalnızca bu ekranda kullanılır, kaydedilmez.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(ssid, { ssid = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Ağ adı (SSID)") }, singleLine = true)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WifiQr.Security.entries.forEach { s -> FilterChip(sec == s, { sec = s }, label = { Text(s.label) }) }
        }
        if (sec != WifiQr.Security.NONE) {
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Şifre") }, singleLine = true,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Gizle" else "Göster") } })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Gizli ağ", Modifier.weight(1f)); Switch(hidden, { hidden = it })
        }
        problem?.takeIf { ssid.isNotBlank() || pass.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = problem == null, onClick = { sp.edit().putString("ssid", ssid.trim()).apply(); show = true },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)) { Text("QR kodu göster") }
        Text("Şifreyi bilmiyorsan: Android'de Ayarlar → Wi-Fi → bağlı ağa dokun → Paylaş. Telefon şifreyi ve QR'ını gösterir.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun DevicesTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (info, _) = rememberInfo()
    val found = remember { mutableStateListOf<NetTools.Device>() }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var finished by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val i = info
        when {
            i == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            !i.wifi || i.ip == null -> Text("Cihaz taraması için Wi-Fi'ye bağlı olmalısın.")
            else -> {
                Text("${i.ssid ?: "Bu ağ"} üzerindeki cihazlar (${i.ip.substringBeforeLast('.')}.1–254)", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (job == null) Button(onClick = {
                        found.clear(); finished = false
                        job = scope.launch {
                            runCatching {
                                NetTools.scan(i, onProgress = { a, b -> scope.launch { progress = a to b } },
                                    onFound = { d -> scope.launch { if (found.none { it.ip == d.ip }) found += d } })
                            }.onSuccess { list -> found.clear(); found += list; finished = true }
                            progress = null; job = null
                        }
                    }) { Text(if (finished) "Yeniden tara" else "Taramayı başlat") }
                    else OutlinedButton(onClick = { job?.cancel(); job = null; progress = null }) { Text("Durdur") }
                    if (found.isNotEmpty()) Text("${found.size} cihaz", style = MaterialTheme.typography.titleSmall)
                }
                progress?.let { (a, b) -> LinearProgressIndicator(progress = { a / b.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
                LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(found, key = { it.ip }) { d ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.kind, style = MaterialTheme.typography.titleSmall)
                                    Text(listOfNotNull(d.ip, d.name).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                    if (d.ports.isNotEmpty()) Text("Açık portlar: " + d.ports.sorted().joinToString(", "), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (80 in d.ports || 443 in d.ports || d.ip == i.gateway) TextButton(onClick = { openRouter(ctx, d.ip) }) { Text("Aç") }
                            }
                        }
                    }
                    if (finished) item {
                        Text("Bazı cihazlar (özellikle telefonlar ve güvenlik duvarı açık bilgisayarlar) taramaya cevap vermeyebilir. Cihaz adları modemin bildirdiği kadarıyla görünür.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (info, _) = rememberInfo()
    var ping by remember { mutableStateOf<Long?>(null) }
    var down by remember { mutableStateOf<Double?>(null) }
    var up by remember { mutableStateOf<Double?>(null) }
    var stage by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var askCell by remember { mutableStateOf(false) }

    fun start() {
        ping = null; down = null; up = null
        job = scope.launch {
            runCatching {
                stage = "Gecikme ölçülüyor"; ping = NetTools.ping()
                stage = "İndirme ölçülüyor"; down = NetTools.download(10) { v -> scope.launch { down = v } }
                stage = "Yükleme ölçülüyor"; up = NetTools.upload(8) { v -> scope.launch { up = v } }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Toast.makeText(ctx, "Test yarıda kaldı: ${it.message}", Toast.LENGTH_LONG).show() }
            stage = null; job = null
        }
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        @Composable fun big(label: String, v: String?, unit: String) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(v ?: "–", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text(" $unit", modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        big("İndirme", down?.let(NetMath::fmtMbps), "Mbps")
        big("Yükleme", up?.let(NetMath::fmtMbps), "Mbps")
        big("Gecikme (ping)", ping?.toString(), "ms")
        stage?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, modifier = Modifier.padding(top = 4.dp)) }
        if (job == null) Button(onClick = { if (info?.cellular == true) askCell = true else start() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)) {
            Text(if (down == null) "Testi başlat" else "Yeniden test et")
        } else OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Durdur") }
        Text("Ölçüm Cloudflare sunucularıyla yapılır; bir test yaklaşık 20 saniye sürer ve hızına göre 20–300 MB veri harcar.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    }
    if (askCell) AlertDialog(onDismissRequest = { askCell = false }, title = { Text("Mobil veri kullanılacak") },
        text = { Text("Wi-Fi'ye bağlı değilsin. Hız testi mobil veri paketinden yüzlerce MB harcayabilir. Devam edilsin mi?") },
        confirmButton = { TextButton(onClick = { askCell = false; start() }) { Text("Devam") } },
        dismissButton = { TextButton(onClick = { askCell = false }) { Text("Vazgeç") } })
}

@Suppress("unused")
private fun copy(ctx: Context, s: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ag", s))
}
