package com.ercan.heybe.tools.media

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
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
import com.ercan.heybe.core.MediaMath
import com.ercan.heybe.core.PlaybackRules
import com.ercan.heybe.tools.pdf.RunStatus
import com.ercan.heybe.tools.pdf.ToolRun
import com.ercan.heybe.ui.rememberWithPermissions
import com.ercan.heybe.ui.storagePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaScreen(initial: List<Uri>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val run = remember { ToolRun() }
    var uri by remember { mutableStateOf(initial.firstOrNull()) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }
    var range by remember { mutableStateOf(0f..1f) }
    var preset by remember { mutableStateOf(CompressPreset.ORTA) }
    val withStorage = rememberWithPermissions(storagePermissions(), "Kaydetmek için depolama izni gerekli.")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; uri = u }
    }
    LaunchedEffect(uri) {
        info = null; err = null; run.result = null; run.error = null
        val u = uri ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { MediaEngine.info(ctx, u) } }
            .onSuccess { info = it; range = 0f..it.durationMs.toFloat().coerceAtLeast(1f); tab = if (it.hasVideo) 0 else 1 }
            .onFailure { err = it.message }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("video/*", "audio/*")) }) { Text(if (uri == null) "Video ya da ses dosyası seç" else "Başka dosya seç") }
            info?.let {
                Text(it.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                Text((if (it.hasVideo) "Video · ${it.width}×${it.height}" else "Ses") + " · " + PlaybackRules.clock(it.durationMs),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        val i = info ?: return@Column
        val tabs = if (i.hasVideo) listOf("Ses çıkar", "Kırp", "Sıkıştır") else listOf("Kırp")
        val current = if (i.hasVideo) tab else 1
        if (i.hasVideo) TabRow(selectedTabIndex = tab) { tabs.forEachIndexed { k, t -> Tab(selected = tab == k, onClick = { tab = k }, text = { Text(t) }) } }

        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            when (current) {
                0 -> {
                    Text("Videonun sesini MP3 olarak kaydeder (Download/Heybe/Ses). Müzik klipleri ve konuşma kayıtları için.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { withStorage { run.start(scope) { r -> MediaEngine.extractAudio(ctx, uri!!, i, r) } } },
                        enabled = !run.busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("MP3 çıkar") }
                }
                1 -> {
                    val dur = i.durationMs.toFloat().coerceAtLeast(1f)
                    Text("Tutmak istediğin bölümü seç.", style = MaterialTheme.typography.bodyMedium)
                    RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..dur, modifier = Modifier.padding(top = 8.dp))
                    fun nudge(start: Boolean, d: Long) {
                        val a = range.start.toLong(); val b = range.endInclusive.toLong()
                        range = if (start) (a + d).coerceIn(0, b - 500).toFloat()..range.endInclusive
                        else range.start..(b + d).coerceIn(a + 500, dur.toLong()).toFloat()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Başlangıç ${MediaMath.stamp(range.start.toLong()).dropLast(2)}", Modifier.weight(1f))
                        OutlinedButton(onClick = { nudge(true, -1000) }) { Text("−1 sn") }
                        Spacer(Modifier.padding(2.dp))
                        OutlinedButton(onClick = { nudge(true, 1000) }) { Text("+1 sn") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bitiş ${MediaMath.stamp(range.endInclusive.toLong()).dropLast(2)}", Modifier.weight(1f))
                        OutlinedButton(onClick = { nudge(false, -1000) }) { Text("−1 sn") }
                        Spacer(Modifier.padding(2.dp))
                        OutlinedButton(onClick = { nudge(false, 1000) }) { Text("+1 sn") }
                    }
                    Text("Seçilen uzunluk: " + PlaybackRules.clock((range.endInclusive - range.start).toLong()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { withStorage { run.start(scope) { r -> MediaEngine.trim(ctx, uri!!, i, range.start.toLong(), range.endInclusive.toLong(), r) } } },
                        enabled = !run.busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Kırp ve kaydet") }
                }
                2 -> {
                    Text("WhatsApp gibi uygulamalarda göndermek için videoyu küçültür. Kaynaktan büyük bir boyuta çıkarılmaz.",
                        style = MaterialTheme.typography.bodyMedium)
                    CompressPreset.entries.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { preset = p }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = preset == p, onClick = { preset = p })
                            Column {
                                Text(p.label)
                                Text("Tahmini: ~%.0f MB".format(MediaMath.estimateMb(i.durationMs, p.bitrate)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Button(onClick = { withStorage { run.start(scope) { r -> MediaEngine.compress(ctx, uri!!, i, preset, r) } } },
                        enabled = !run.busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Sıkıştır") }
                }
            }
            Spacer(Modifier.height(4.dp))
            RunStatus(run)
        }
    }
}
