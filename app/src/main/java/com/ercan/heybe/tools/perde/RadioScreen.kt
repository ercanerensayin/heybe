package com.ercan.heybe.tools.perde

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.ercan.heybe.core.RadioStations
import com.ercan.heybe.core.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** İnternet radyosu: Radio Browser (radio-browser.info) açık rehberi + kendi eklediğin adresler. */
object RadioRepo {
    private val mirrors = listOf("https://de1.api.radio-browser.info", "https://nl1.api.radio-browser.info", "https://at1.api.radio-browser.info", "https://all.api.radio-browser.info")

    private fun get(path: String): String {
        var last: Exception? = null
        for (m in mirrors) {
            try {
                val c = (URL(m + path).openConnection() as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 12000; setRequestProperty("User-Agent", "Heybe/1.0") }
                try { if (c.responseCode == 200) return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Radyo rehberine ulaşılamadı")
    }

    private fun parse(json: String): List<Station> {
        val a = JSONArray(json)
        return RadioStations.dedupe((0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Station(o.optString("name").trim(), o.optString("url_resolved").ifBlank { o.optString("url") }, o.optString("favicon"),
                o.optString("stationuuid"), o.optString("tags"), o.optInt("bitrate"))
        }.filter { it.name.isNotBlank() })
    }

    /** Türkiye'de en çok dinlenenler ya da ada göre arama (dünya geneli). */
    fun list(query: String): List<Station> {
        val q = query.trim()
        val path = if (q.isEmpty()) "/json/stations/search?countrycode=TR&order=clickcount&reverse=true&hidebroken=true&limit=200"
        else "/json/stations/search?name=${URLEncoder.encode(q, "UTF-8")}&order=clickcount&reverse=true&hidebroken=true&limit=100"
        return parse(get(path))
    }

    /** Rehberin istediği gibi dinlenmeyi bildirir (sıralama için). */
    fun click(id: String) { if (id.isNotBlank()) runCatching { get("/json/url/$id") } }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("radyo", Context.MODE_PRIVATE)
    fun favorites(ctx: Context) = RadioStations.decode(sp(ctx).getString("fav", null))
    fun setFavorites(ctx: Context, l: List<Station>) { sp(ctx).edit().putString("fav", RadioStations.encode(l)).apply() }
}

@Composable
fun RadioTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = rememberMusicController()
    var favs by remember { mutableStateOf(RadioRepo.favorites(ctx)) }
    var all by remember { mutableStateOf<List<Station>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }
    var nowId by remember { mutableStateOf<String?>(null) }
    var nowTitle by remember { mutableStateOf<String?>(null) }
    var nowSong by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }

    fun load(q: String) {
        scope.launch {
            loading = true; error = null
            runCatching { withContext(Dispatchers.IO) { RadioRepo.list(q) } }
                .onSuccess { all = it }.onFailure { error = "İstasyon listesi alınamadı. İnternet bağlantını kontrol et." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load("") }

    DisposableEffect(controller) {
        val c = controller
        fun sync(player: Player) {
            val id = player.currentMediaItem?.mediaId
            nowId = id?.takeIf { it.startsWith("radyo:") }
            val md = player.mediaMetadata
            nowTitle = (md.station ?: md.title)?.toString()?.takeIf { nowId != null }
            nowSong = md.title?.toString()?.takeIf { nowId != null && it.isNotBlank() && it != nowTitle }
            playing = player.isPlaying; buffering = player.playbackState == Player.STATE_BUFFERING
        }
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(ctx, "Bu istasyon şu an çalınamıyor.", Toast.LENGTH_LONG).show()
            }
        }
        c?.addListener(l); c?.let { sync(it) }
        onDispose { c?.removeListener(l) }
    }

    fun play(s: Station) {
        val c = controller ?: return
        val md = MediaMetadata.Builder().setTitle(s.name).setStation(s.name).setArtist("Radyo")
            .apply { if (s.logo.startsWith("http")) setArtworkUri(Uri.parse(s.logo)) }.build()
        c.setMediaItem(MediaItem.Builder().setUri(s.url).setMediaId("radyo:" + RadioStations.key(s)).setMediaMetadata(md).build())
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.prepare(); c.play()
        scope.launch(Dispatchers.IO) { RadioRepo.click(s.id) }
    }
    fun toggleFav(s: Station) {
        val k = RadioStations.key(s)
        favs = if (favs.any { RadioStations.key(it) == k }) favs.filter { RadioStations.key(it) != k } else favs + s
        RadioRepo.setFavorites(ctx, favs)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("İstasyon ara (ör. TRT, Kral, jazz)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { load(query) }),
                    trailingIcon = { TextButton(onClick = { load(query) }) { Text("Ara") } })
                Row(Modifier.padding(top = 4.dp)) {
                    if (query.isNotEmpty()) TextButton(onClick = { query = ""; load("") }) { Text("Türkiye listesine dön") }
                    TextButton(onClick = { add = true }) { Text("Adresle istasyon ekle") }
                }
            }
            if (favs.isNotEmpty()) {
                item { Text("Favoriler", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }
                items(favs, key = { "f" + RadioStations.key(it) }) { s -> StationRow(s, true, nowId == "radyo:" + RadioStations.key(s), { play(s) }, { toggleFav(s) }) }
            }
            item {
                Text(if (query.isBlank()) "Türkiye'de en çok dinlenenler" else "Arama sonuçları", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                if (!loading && error == null && all.isEmpty()) Text("Sonuç yok.", modifier = Modifier.padding(top = 6.dp))
            }
            items(all, key = { "a" + RadioStations.key(it) }) { s ->
                StationRow(s, favs.any { RadioStations.key(it) == RadioStations.key(s) }, nowId == "radyo:" + RadioStations.key(s), { play(s) }, { toggleFav(s) })
            }
            item {
                Text("İstasyon listesi radio-browser.info açık rehberinden gelir; bazı yayınlar zaman zaman kapalı olabilir.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        val c = controller
        if (nowId != null && c != null) {
            Surface(tonalElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(nowTitle ?: "Radyo", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (buffering) "Bağlanıyor…" else if (!playing) "Duraklatıldı" else nowSong ?: "Canlı yayın · ekran kapalıyken de çalar",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { if (c.isPlaying) c.pause() else { c.prepare(); c.play() } }) { Text(if (playing) "❚❚" else "▶", fontSize = 20.sp) }
                    TextButton(onClick = { c.stop(); c.clearMediaItems() }) { Text("■", fontSize = 18.sp) }
                }
            }
        }
    }

    if (add) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { add = false },
            title = { Text("İstasyon ekle") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Ad") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("Yayın adresi (http…)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Radyonun sitesindeki \"canlı dinle\" yayın adresini (mp3, aac ya da m3u8) yapıştır.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && RadioStations.validUrl(url), onClick = {
                    add = false; favs = favs + Station(name.trim(), url.trim()); RadioRepo.setFavorites(ctx, favs)
                }) { Text("Ekle") }
            },
            dismissButton = { TextButton(onClick = { add = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun StationRow(s: Station, fav: Boolean, now: Boolean, onPlay: () -> Unit, onFav: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onPlay),
        colors = if (now) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (now) "▶ " else "") + s.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(s.tags.split(',').take(3).joinToString(", ").takeIf { it.isNotBlank() }, s.bitrate.takeIf { it > 0 }?.let { "$it kbps" })
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onFav) { Text(if (fav) "★" else "☆", fontSize = 22.sp) }
        }
    }
}
