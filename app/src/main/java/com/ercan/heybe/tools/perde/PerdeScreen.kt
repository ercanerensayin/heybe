package com.ercan.heybe.tools.perde

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ercan.heybe.core.PlaybackRules
import com.ercan.heybe.data.HeybeDb
import com.ercan.heybe.data.Playback
import kotlinx.coroutines.launch

private fun videoPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun PerdeScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TabRow(selectedTabIndex = tab) {
            androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Videolar") })
            androidx.compose.material3.Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Müzik") })
        }
        if (tab == 0) VideoLibrary() else MusicTab()
    }
}

@Composable
private fun VideoLibrary() {
    val ctx = LocalContext.current
    val dao = remember { HeybeDb.get(ctx).playback() }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, videoPermission()) == PackageManager.PERMISSION_GRANTED) }
    var folders by remember { mutableStateOf<List<VideoFolder>?>(null) }
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val saved by dao.all().collectAsState(initial = emptyList())
    val byKey = remember(saved) { saved.associateBy { it.key } }
    val recents by dao.recents().collectAsState(initial = emptyList())

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            PlayerActivity.start(ctx, listOf(uri), 0)
        }
    }
    LaunchedEffect(granted) { if (granted) folders = PerdeLibrary.folders(ctx) }

    fun playIn(list: List<VideoFile>, v: VideoFile) = PlayerActivity.start(ctx, list.map { it.uri }, list.indexOf(v).coerceAtLeast(0))
    fun playKey(key: String) {
        val uri = Uri.parse(key)
        val folder = folders?.firstOrNull { f -> f.videos.any { it.uri == uri } }
        if (folder != null) playIn(folder.videos, folder.videos.first { it.uri == uri }) else PlayerActivity.start(ctx, listOf(uri), 0)
    }

    val current = folders?.firstOrNull { it.name == openFolder }
    BackHandler(enabled = current != null) { openFolder = null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("video/*")) }) { Text("Dosya seç") }
                }
            }
            val rec = recents.filter { r -> r.position > 0 }
            if (rec.isNotEmpty()) {
                item { Text("Son oynatılanlar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(rec, key = { it.key }) { p -> RecentCard(p) { playKey(p.key) } }
                    }
                }
            }
            if (!granted) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Telefonundaki videoları klasörlere göre listelemek için videolara erişim izni gerekli.")
                            Button(onClick = { askPermission.launch(videoPermission()) }, modifier = Modifier.padding(top = 8.dp)) { Text("İzin ver") }
                        }
                    }
                }
            } else if (folders == null) {
                item { Text("Videolar taranıyor…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (folders!!.isEmpty()) {
                item { Text("Telefonda video bulunamadı.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { Text("Klasörler", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                items(folders!!, key = { "d_" + it.name }) { f ->
                    val watched = f.videos.count { byKey[it.uri.toString()]?.watched == true }
                    Card(Modifier.fillMaxWidth().clickable { openFolder = f.name }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumb(f.videos.first().uri, Modifier.width(96.dp).height(56.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${f.videos.size} video" + if (watched > 0) " · $watched izlendi" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openFolder = null }) { Text("‹ Klasörler") }
                    Text(current.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            items(current.videos, key = { it.uri.toString() }) { v ->
                VideoRow(v, byKey[v.uri.toString()], onPlay = { playIn(current.videos, v) })
            }
        }
    }
}

@Composable
private fun Thumb(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) { value = PerdeLibrary.thumbnail(ctx, uri) }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text("▶", color = Color.Gray)
    }
}

@Composable
private fun RecentCard(p: Playback, onClick: () -> Unit) {
    Column(Modifier.width(180.dp).clickable(onClick = onClick)) {
        Box {
            Thumb(Uri.parse(p.key), Modifier.width(180.dp).height(101.dp))
            LinearProgressIndicator(progress = { PlaybackRules.progress(p.position, p.duration) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                color = Color(0xFFE53935), trackColor = Color(0x66FFFFFF))
        }
        Text(p.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text("${PlaybackRules.clock(p.position)} / ${PlaybackRules.clock(p.duration)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(v: VideoFile, p: Playback?, onPlay: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onPlay, onLongClick = { menu = true }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box {
                Thumb(v.uri, Modifier.width(128.dp).height(72.dp))
                if (p != null && !p.watched && p.position > PlaybackRules.MIN_RESUME_MS)
                    LinearProgressIndicator(progress = { PlaybackRules.progress(p.position, p.duration) },
                        modifier = Modifier.align(Alignment.BottomCenter).width(128.dp).height(3.dp),
                        color = Color(0xFFE53935), trackColor = Color(0x66FFFFFF))
                if (p?.watched == true)
                    Text("✓", color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0xCC43A047), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(PlaybackRules.clock(v.duration) + " · " + Formatter.formatShortFileSize(ctx, v.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Oynat") }, onClick = { menu = false; onPlay() })
            if (p != null) DropdownMenuItem(text = { Text("İzlenmedi olarak işaretle") }, onClick = {
                menu = false; scope.launch { HeybeDb.get(ctx).playback().clear(v.uri.toString()) }
            })
        }
    }
}
