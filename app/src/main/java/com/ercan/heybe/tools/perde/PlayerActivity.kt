package com.ercan.heybe.tools.perde

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.ercan.heybe.core.PlaybackRules
import com.ercan.heybe.core.SubtitleText
import com.ercan.heybe.data.HeybeDb
import com.ercan.heybe.data.Playback
import com.ercan.heybe.ui.theme.HeybeTheme
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Perde'nin tam ekran oynatıcısı. */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_LIST = "perde_list"
        private const val EXTRA_INDEX = "perde_index"

        fun start(ctx: Context, list: List<Uri>, index: Int) {
            if (list.isEmpty()) return
            val i = Intent(ctx, PlayerActivity::class.java)
                .setData(list[index])
                .putStringArrayListExtra(EXTRA_LIST, ArrayList(list.map { it.toString() }))
                .putExtra(EXTRA_INDEX, index)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    val state = PlayerState()
    private var playlist: List<Uri> = emptyList()
    private var index = 0
    private var current: Playback? = null
    private var subFile: Pair<Uri, String>? = null
    private var countdownJob: Job? = null
    private var videoRatio = Rational(16, 9)
    private val dao by lazy { HeybeDb.get(this).playback() }
    private val prefs by lazy { getSharedPreferences("perde", MODE_PRIVATE) }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSubtitlePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        state.subSize = runCatching { SubSize.valueOf(prefs.getString("altyazi_boyut", "ORTA")!!) }.getOrDefault(SubSize.ORTA)
        state.subBackground = prefs.getBoolean("altyazi_kutu", true)

        val renderers = NextRenderersFactory(applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers).build()
        player.addListener(listener)
        session = runCatching { MediaSession.Builder(this, player).build() }.getOrNull()

        readIntent(intent)
        setContent { HeybeTheme { PlayerScreen(player, state, actions) } }
        startTicker()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent) {
        val list = intent.getStringArrayListExtra(EXTRA_LIST)?.map(Uri::parse)
        playlist = list ?: listOfNotNull(intent.data)
        if (playlist.isEmpty()) { finish(); return }
        load(intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, playlist.lastIndex))
    }

    // ---------- Yükleme ----------

    private fun load(i: Int, startAt: Long? = null) {
        saveNow()
        countdownJob?.cancel(); state.countdown = null
        index = i
        val uri = playlist[i]
        val key = uri.toString()
        lifecycleScope.launch {
            val title = withContext(Dispatchers.IO) { PerdeLibrary.displayName(this@PlayerActivity, uri) }
            val saved = withContext(Dispatchers.IO) { dao.get(key) } ?: Playback(key, title)
            current = saved.copy(title = title)
            state.title = title
            state.hasNext = i < playlist.lastIndex
            state.nextTitle = null
            state.subOffset = saved.subOffsetMs
            state.textOff = saved.textOff
            state.error = null

            val subSrc = saved.subUri?.let(Uri::parse) ?: withContext(Dispatchers.IO) { findSidecar(uri) }
            subFile = subSrc?.let { withContext(Dispatchers.IO) { runCatching { prepareSubtitle(it, saved.subOffsetMs) }.getOrNull() } }
            state.externalSub = subSrc?.let { PerdeLibrary.displayName(this@PlayerActivity, it) }.takeIf { subFile != null }

            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverrides()
                .setPreferredAudioLanguage(saved.audioLang)
                .setPreferredTextLanguages(*listOfNotNull(saved.textLang, "tr").distinct().toTypedArray())
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, saved.textOff)
                .build()
            val resume = startAt ?: PlaybackRules.resumeAt(saved.position, saved.duration, saved.watched)
            player.setMediaItem(buildItem(uri), resume)
            player.setPlaybackSpeed(state.speed)
            player.prepare()
            player.play()
            state.resumedFrom = resume
            if (resume > 0) launch { delay(7000); state.resumedFrom = 0 }
            if (state.hasNext) state.nextTitle = withContext(Dispatchers.IO) { PerdeLibrary.displayName(this@PlayerActivity, playlist[i + 1]) }
        }
    }

    private fun buildItem(uri: Uri): MediaItem {
        val b = MediaItem.Builder().setUri(uri)
        subFile?.let { (u, mime) ->
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(u).setMimeType(mime).setLanguage("tr")
                    .setLabel("Harici altyazı").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            ))
        }
        return b.build()
    }

    /** Videonun yanında aynı adla duran altyazı dosyası (erişilebiliyorsa). */
    private fun findSidecar(video: Uri): Uri? {
        val path = (if (video.scheme == "file") video.path else PerdeLibrary.pathOf(this, video)) ?: return null
        val base = path.substringBeforeLast('.')
        for (suffix in listOf(".tr.srt", ".srt", ".tur.srt", ".ass", ".ssa", ".vtt")) {
            val f = File(base + suffix)
            if (runCatching { f.exists() && f.canRead() }.getOrDefault(false)) return Uri.fromFile(f)
        }
        return null
    }

    /** Altyazıyı okur, kodlamasını UTF-8'e çevirir, zamanı kaydırır ve önbelleğe yazar. */
    private fun prepareSubtitle(src: Uri, offset: Long): Pair<Uri, String> {
        val name = PerdeLibrary.displayName(this, src)
        val fmt = SubtitleText.formatOf(name) ?: SubtitleText.Format.SRT
        val bytes = contentResolver.openInputStream(src)?.use { it.readBytes() } ?: error("Altyazı okunamadı")
        val text = SubtitleText.shift(SubtitleText.decode(bytes), offset, fmt)
        val dir = File(cacheDir, "perde").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "altyazi_${System.nanoTime()}.${fmt.ext}")
        out.writeText(text, Charsets.UTF_8)
        val mime = when (fmt) {
            SubtitleText.Format.SRT -> MimeTypes.APPLICATION_SUBRIP
            SubtitleText.Format.VTT -> MimeTypes.TEXT_VTT
            SubtitleText.Format.ASS -> MimeTypes.TEXT_SSA
        }
        return Uri.fromFile(out) to mime
    }

    private fun reloadKeepingPosition() {
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.setMediaItem(buildItem(playlist[index]), pos)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    private fun onSubtitlePicked(uri: Uri) {
        val name = PerdeLibrary.displayName(this, uri)
        if (SubtitleText.formatOf(name) == null) {
            Toast.makeText(this, "Desteklenen altyazı biçimleri: .srt, .ass, .ssa, .vtt", Toast.LENGTH_LONG).show(); return
        }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { prepareSubtitle(uri, 0) } }
            r.onSuccess {
                subFile = it
                state.externalSub = name; state.subOffset = 0; state.textOff = false
                current = current?.copy(subUri = uri.toString(), subOffsetMs = 0, textOff = false)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage("tr").build()
                reloadKeepingPosition(); saveNow()
            }.onFailure { Toast.makeText(this@PlayerActivity, "Altyazı açılamadı: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun shiftSubtitle(deltaMs: Long) {
        val src = current?.subUri?.let(Uri::parse) ?: findSidecar(playlist[index]) ?: return
        val newOffset = state.subOffset + deltaMs
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { prepareSubtitle(src, newOffset) } }
            r.onSuccess {
                subFile = it; state.subOffset = newOffset
                current = current?.copy(subOffsetMs = newOffset)
                reloadKeepingPosition(); saveNow()
            }
        }
    }

    // ---------- Kayıt ----------

    private fun saveNow() {
        val p = current ?: return
        if (!::player.isInitialized) return
        val dur = player.duration.takeIf { it > 0 } ?: p.duration
        val pos = player.currentPosition.coerceAtLeast(0)
        if (dur <= 0 && pos <= 0) return
        val np = p.copy(position = pos, duration = dur, lastPlayed = System.currentTimeMillis(),
            watched = p.watched || PlaybackRules.isWatched(pos, dur))
        current = np
        lifecycleScope.launch(Dispatchers.IO + NonCancellable) { dao.upsert(np) }
    }

    private fun startTicker() = lifecycleScope.launch {
        var n = 0
        while (isActive) {
            state.position = player.currentPosition
            state.duration = player.duration.coerceAtLeast(0)
            if (++n % 10 == 0 && player.isPlaying) saveNow()
            delay(500)
        }
    }

    // ---------- Olaylar ----------

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            state.playing = isPlaying
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); saveNow() }
            updatePip()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            state.buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_ENDED) {
                current = current?.copy(watched = true)
                saveNow()
                if (index < playlist.lastIndex) startCountdown() else state.controls = true
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            videoRatio = Rational(w, videoSize.height)
            if (!state.rotationLocked) applyVideoOrientation()
            updatePip()
        }

        override fun onTracksChanged(tracks: Tracks) = readTracks(tracks)

        override fun onPlayerError(error: PlaybackException) {
            state.error = "Oynatılamadı: " + (error.cause?.message ?: error.errorCodeName)
        }
    }

    private fun applyVideoOrientation() {
        requestedOrientation = if (videoRatio.toFloat() >= 1f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun langLabel(lang: String?): String? {
        if (lang.isNullOrBlank() || lang == "und") return null
        val tr = Locale.forLanguageTag("tr")
        return Locale.forLanguageTag(lang).getDisplayLanguage(tr).replaceFirstChar { it.titlecase(tr) }.ifBlank { null }
    }

    private fun readTracks(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>(); val text = mutableListOf<TrackOption>()
        for (g in tracks.groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        val ch = when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
                        val label = listOfNotNull(f.label ?: langLabel(f.language) ?: "Ses ${audio.size + 1}", ch).joinToString(" · ")
                        audio += TrackOption(label, g.mediaTrackGroup, i, f.language, g.isTrackSelected(i))
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val label = f.label ?: langLabel(f.language) ?: "Altyazı ${text.size + 1}"
                        text += TrackOption(label, g.mediaTrackGroup, i, f.language, g.isTrackSelected(i))
                    }
                }
            }
        }
        state.audioTracks = audio; state.textTracks = text
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            for (s in 5 downTo 1) { state.countdown = s; delay(1000) }
            state.countdown = null
            load(index + 1, startAt = null)
        }
    }

    // ---------- PiP ----------

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val r = videoRatio.toFloat()
        val ratio = when { r > 2.39f -> Rational(239, 100); r < 1 / 2.39f -> Rational(100, 239); else -> videoRatio }
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(player.isPlaying).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePip() {
        if (Build.VERSION.SDK_INT >= 26) pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature("android.software.picture_in_picture")) {
            Toast.makeText(this, "Bu telefon ekran içinde ekranı desteklemiyor.", Toast.LENGTH_SHORT).show(); return
        }
        state.controls = false; state.sheet = Sheet.NONE
        pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        state.inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode) state.controls = true
    }

    override fun onStop() {
        super.onStop()
        saveNow()
        // PiP penceresi kapatıldığında ya da uygulamadan çıkıldığında dur.
        if (!(Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)) player.pause()
    }

    override fun onDestroy() {
        saveNow()
        session?.release()
        player.release()
        super.onDestroy()
    }

    // ---------- Arayüz eylemleri ----------

    val actions = object : PlayerActions {
        override fun back() = finish()
        override fun togglePlay() {
            if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0); player.play() }
            else if (player.isPlaying) player.pause() else player.play()
        }
        override fun seekBy(ms: Long) {
            player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.coerceAtLeast(0)))
            state.seekFlash = if (ms < 0) -1 else 1; state.seekFlashSeq++
        }
        override fun seekTo(ms: Long) = player.seekTo(ms)
        override fun restart() { player.seekTo(0); state.resumedFrom = 0 }
        override fun next() { if (index < playlist.lastIndex) load(index + 1) }
        override fun cancelCountdown() { countdownJob?.cancel(); state.countdown = null; state.controls = true }
        override fun playNextNow() { countdownJob?.cancel(); state.countdown = null; next() }
        override fun pip() = enterPip()
        override fun setSpeed(s: Float) { state.speed = s; player.setPlaybackSpeed(s); state.sheet = Sheet.NONE }
        override fun setAspect(a: Aspect) { state.aspect = a; state.sheet = Sheet.NONE }
        override fun toggleRotationLock() {
            state.rotationLocked = !state.rotationLocked
            if (state.rotationLocked) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED else applyVideoOrientation()
            Toast.makeText(this@PlayerActivity, if (state.rotationLocked) "Ekran yönü kilitlendi" else "Ekran yönü videoya göre", Toast.LENGTH_SHORT).show()
        }
        override fun selectAudio(t: TrackOption) {
            val g = t.group ?: return
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(g, t.index)).build()
            current = current?.copy(audioLang = t.language); saveNow()
        }
        override fun selectText(t: TrackOption?) {
            val b = player.trackSelectionParameters.buildUpon()
            if (t?.group == null) { b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); state.textOff = true; current = current?.copy(textOff = true) }
            else {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(t.group, t.index))
                state.textOff = false; current = current?.copy(textOff = false, textLang = t.language)
            }
            player.trackSelectionParameters = b.build(); saveNow()
        }
        override fun pickSubtitleFile() = pickSubtitle.launch(arrayOf("*/*"))
        override fun loadSubtitle(uri: Uri) { state.sheet = Sheet.NONE; onSubtitlePicked(uri) }
        override fun shiftSubtitle(deltaMs: Long) = this@PlayerActivity.shiftSubtitle(deltaMs)
        override fun setSubSize(s: SubSize) { state.subSize = s; prefs.edit().putString("altyazi_boyut", s.name).apply() }
        override fun toggleSubBackground() { state.subBackground = !state.subBackground; prefs.edit().putBoolean("altyazi_kutu", state.subBackground).apply() }
    }
}

interface PlayerActions {
    fun back(); fun togglePlay(); fun seekBy(ms: Long); fun seekTo(ms: Long); fun restart(); fun next()
    fun cancelCountdown(); fun playNextNow(); fun pip(); fun setSpeed(s: Float); fun setAspect(a: Aspect)
    fun toggleRotationLock(); fun selectAudio(t: TrackOption); fun selectText(t: TrackOption?)
    fun pickSubtitleFile(); fun loadSubtitle(uri: Uri); fun shiftSubtitle(deltaMs: Long); fun setSubSize(s: SubSize); fun toggleSubBackground()
}
