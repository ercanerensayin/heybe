package com.ercan.heybe.tools.perde

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ercan.heybe.core.PlaybackRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class Song(val uri: Uri, val title: String, val artist: String, val duration: Long, val folder: String)

object MusicLibrary {
    suspend fun folders(ctx: Context): List<Pair<String, List<Song>>> = withContext(Dispatchers.IO) {
        val col = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA)
        val list = mutableListOf<Song>()
        ctx.contentResolver.query(col, proj, "${MediaStore.Audio.Media.DURATION} >= 20000", null, null)?.use { c ->
            while (c.moveToNext()) {
                val artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: ""
                val folder = c.getString(4)?.let { File(it).parentFile?.name } ?: "Diğer"
                list += Song(ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: "Şarkı", artist, c.getLong(3), folder)
            }
        }
        list.groupBy { it.folder }.map { (k, v) -> k to v.sortedBy { it.title.lowercase() } }.sortedBy { it.first.lowercase() }
    }

    fun item(s: Song): MediaItem = MediaItem.Builder().setUri(s.uri).setMediaId(s.uri.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist.ifEmpty { null }).build()).build()
}

private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
internal fun rememberMusicController(): MediaController? {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, MusicService::class.java))).buildAsync()
        f.addListener({ controller = runCatching { f.get() }.getOrNull() }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }
    return controller
}

@Composable
private fun AlbumArt(uri: Uri?, size: Int) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) { value = uri?.let { u -> withContext(Dispatchers.IO) { if (Build.VERSION.SDK_INT >= 29) runCatching { ctx.contentResolver.loadThumbnail(u, android.util.Size(200, 200), null) }.getOrNull() else null } } }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A2A4A)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } ?: Text("♪", color = Color.White, fontSize = (size / 2).sp)
    }
}

@Composable
fun MusicTab() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    var folders by remember { mutableStateOf<List<Pair<String, List<Song>>>?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val controller = rememberMusicController()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it[audioPermission()] == true }
    LaunchedEffect(granted) { if (granted) folders = MusicLibrary.folders(ctx) }

    // Çalan şarkının durumu
    var playing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf("") }
    var nowUri by remember { mutableStateOf<Uri?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    DisposableEffect(controller) {
        val c = controller
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playing = player.isPlaying; shuffle = player.shuffleModeEnabled
                val m = player.currentMediaItem
                title = m?.mediaMetadata?.title?.toString(); artist = m?.mediaMetadata?.artist?.toString() ?: ""
                nowUri = m?.mediaId?.let(Uri::parse)
            }
        }
        c?.addListener(l); c?.let { l.onEvents(it, Player.Events(androidx.media3.common.FlagSet.Builder().build())) }
        onDispose { c?.removeListener(l) }
    }
    LaunchedEffect(controller) { while (true) { controller?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0) }; delay(500) } }

    fun play(list: List<Song>, index: Int) {
        val c = controller ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ask.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        c.setMediaItems(list.map(MusicLibrary::item), index, 0); c.repeatMode = Player.REPEAT_MODE_ALL
        c.prepare(); c.play()
    }

    val current = folders?.firstOrNull { it.first == open }
    BackHandler(enabled = current != null) { open = null }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!granted) item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text("Telefondaki müzikleri listelemek için ses dosyalarına erişim izni gerekli.")
                    Button(onClick = { ask.launch(arrayOf(audioPermission())) }, modifier = Modifier.padding(top = 8.dp)) { Text("İzin ver") }
                } }
            } else if (folders == null) item { Text("Müzikler taranıyor…") }
            else if (folders!!.isEmpty()) item { Text("Telefonda müzik bulunamadı (20 saniyeden kısa sesler gösterilmez).") }
            else if (current == null) {
                items(folders!!, key = { "f" + it.first }) { (name, songs) ->
                    Card(Modifier.fillMaxWidth().clickable { open = name }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AlbumArt(songs.first().uri, 48); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${songs.size} şarkı", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { play(songs, 0) }) { Text("Tümünü çal") }
                        }
                    }
                }
            } else {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { open = null }) { Text("‹ Klasörler") }
                    Text(current.first, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }
                items(current.second, key = { it.uri.toString() }) { s ->
                    val i = current.second.indexOf(s)
                    Row(Modifier.fillMaxWidth().clickable { play(current.second, i) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArt(s.uri, 44); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (s.uri == nowUri) FontWeight.Bold else FontWeight.Normal,
                                color = if (s.uri == nowUri) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(listOf(s.artist, PlaybackRules.clock(s.duration)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        // Mini oynatıcı
        if (title != null && controller != null) {
            Surface(tonalElevation = 4.dp) {
                Column {
                    LinearProgressIndicator(progress = { PlaybackRules.progress(pos, dur) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArt(nowUri, 44); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                            Text(listOf(artist, "${PlaybackRules.clock(pos)} / ${PlaybackRules.clock(dur)}").filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        TextButton(onClick = { controller.shuffleModeEnabled = !controller.shuffleModeEnabled }) { Text(if (shuffle) "Karışık ✓" else "Karışık", fontSize = 12.sp) }
                        TextButton(onClick = { controller.seekToPreviousMediaItem() }) { Text("⏮") }
                        TextButton(onClick = { if (controller.isPlaying) controller.pause() else controller.play() }) { Text(if (playing) "❚❚" else "▶", fontSize = 18.sp) }
                        TextButton(onClick = { controller.seekToNextMediaItem() }) { Text("⏭") }
                    }
                }
            }
        }
    }
}
