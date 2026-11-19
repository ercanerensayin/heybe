package com.ercan.heybe.tools.perde

import android.graphics.Color as AColor
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.ercan.heybe.core.PlaybackRules
import kotlinx.coroutines.delay

private val Scrim = Color(0x99000000)

@Composable
fun PlayerScreen(player: ExoPlayer, s: PlayerState, a: PlayerActions) {
    // Kontroller 3 sn sonra gizlenir (oynarken ve menü kapalıyken).
    var lastTouch by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.controls, s.playing, s.sheet, lastTouch) {
        if (s.controls && s.playing && s.sheet == Sheet.NONE) { delay(3000); s.controls = false }
    }
    val pipThreshold = with(LocalDensity.current) { 140.dp.toPx() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    setShutterBackgroundColor(AColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { v ->
                v.resizeMode = s.aspect.mode
                v.subtitleView?.apply {
                    setFractionalTextSize(if (s.inPip) s.subSize.fraction * 1.6f else s.subSize.fraction)
                    setStyle(CaptionStyleCompat(
                        AColor.WHITE,
                        if (s.subBackground) 0x99000000.toInt() else AColor.TRANSPARENT,
                        AColor.TRANSPARENT,
                        if (s.subBackground) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        AColor.BLACK, Typeface.DEFAULT_BOLD,
                    ))
                    setBottomPaddingFraction(0.06f)
                }
            },
        )
        if (s.inPip) return@Box

        // Dokunma katmanı: tek dokunuş kontrolleri açar/kapatır, çift dokunuş ±10 sn, aşağı kaydırma PiP.
        if (!s.screenLocked) {
            var drag by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { s.controls = !s.controls; lastTouch = System.currentTimeMillis() },
                            onDoubleTap = { o -> a.seekBy(if (o.x < size.width / 2) -10_000 else 10_000) },
                        )
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = { if (drag > pipThreshold) a.pip(); drag = 0f },
                            onVerticalDrag = { _, dy -> drag += dy },
                        )
                    }
            )
        }

        SeekFlash(s)

        if (s.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        if (s.screenLocked) {
            IconButton(
                onClick = { s.screenLocked = false; s.controls = true },
                modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(16.dp).background(Scrim, CircleShape),
            ) { Icon(Icons.Filled.Lock, "Kilidi aç", tint = Color.White) }
            return@Box
        }

        AnimatedVisibility(s.controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(s, a) { lastTouch = System.currentTimeMillis() }
        }

        if (s.resumedFrom > 0 && s.countdown == null) {
            Row(
                Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 16.dp, bottom = 96.dp)
                    .background(Scrim, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Kaldığın yerden: ${PlaybackRules.clock(s.resumedFrom)}", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text("Başa dön", color = Color(0xFFFFC107), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clickable { a.restart() })
            }
        }

        s.countdown?.let { n ->
            Column(
                Modifier.align(Alignment.Center).background(Color(0xDD000000), RoundedCornerShape(16.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sıradaki", color = Color.LightGray, fontSize = 13.sp)
                Text(s.nextTitle ?: "", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$n", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = a::cancelCountdown) { Text("İptal", color = Color.White) }
                    OutlinedButton(onClick = a::playNextNow) { Text("Şimdi oynat", color = Color.White) }
                }
            }
        }

        s.error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Scrim, RoundedCornerShape(8.dp)).padding(16.dp))
        }

        when (s.sheet) {
            Sheet.TRACKS -> TracksDialog(s, a)
            Sheet.SPEED -> ChoiceDialog("Oynatma hızı", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { "${it}x".replace(".0x", "x") to it },
                s.speed, onPick = a::setSpeed, onClose = { s.sheet = Sheet.NONE })
            Sheet.ASPECT -> ChoiceDialog("Görüntü oranı", Aspect.entries.map { it.label to it }, s.aspect, onPick = a::setAspect, onClose = { s.sheet = Sheet.NONE })
            Sheet.NONE -> {}
        }
    }
}

@Composable
private fun SeekFlash(s: PlayerState) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(s.seekFlashSeq) { if (s.seekFlashSeq > 0) { visible = true; delay(600); visible = false } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(if (s.seekFlash < 0) Alignment.CenterStart else Alignment.CenterEnd)) {
            Box(Modifier.padding(horizontal = 32.dp).size(110.dp).background(Color(0x55FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Text(if (s.seekFlash < 0) "« 10 sn" else "10 sn »", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Glyph(kind: String, size: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width; val h = this.size.height
        when (kind) {
            "play" -> drawPath(Path().apply { moveTo(w * 0.28f, h * 0.18f); lineTo(w * 0.85f, h * 0.5f); lineTo(w * 0.28f, h * 0.82f); close() }, Color.White)
            "pause" -> {
                drawRect(Color.White, Offset(w * 0.24f, h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.17f, h * 0.64f))
                drawRect(Color.White, Offset(w * 0.59f, h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.17f, h * 0.64f))
            }
            "next" -> {
                drawPath(Path().apply { moveTo(w * 0.2f, h * 0.2f); lineTo(w * 0.68f, h * 0.5f); lineTo(w * 0.2f, h * 0.8f); close() }, Color.White)
                drawRect(Color.White, Offset(w * 0.7f, h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.1f, h * 0.6f))
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun Controls(s: PlayerState, a: PlayerActions, touched: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // Üst
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = a::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
            Text(s.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Pill("Ses/Altyazı") { s.sheet = Sheet.TRACKS; touched() }
            Spacer(Modifier.width(8.dp))
        }

        // Orta
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Text("−10", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Scrim, CircleShape).clickable { a.seekBy(-10_000); touched() }.padding(14.dp))
            Box(Modifier.size(76.dp).background(Scrim, CircleShape).clickable { a.togglePlay(); touched() }, contentAlignment = Alignment.Center) {
                Glyph(if (s.playing) "pause" else "play", 40)
            }
            Text("+10", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Scrim, CircleShape).clickable { a.seekBy(10_000); touched() }.padding(14.dp))
        }

        // Alt
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            val dur = s.duration.coerceAtLeast(1)
            val shown = dragging?.let { (it * dur).toLong() } ?: s.position
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PlaybackRules.clock(shown), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = dragging ?: PlaybackRules.progress(s.position, s.duration),
                    onValueChange = { dragging = it; touched() },
                    onValueChangeFinished = { dragging?.let { a.seekTo((it * dur).toLong()) }; dragging = null },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(32.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFE53935), inactiveTrackColor = Color(0x66FFFFFF)),
                )
                Text(PlaybackRules.clock(s.duration), color = Color.White, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(("${s.speed}x").replace(".0x", "x")) { s.sheet = Sheet.SPEED; touched() }
                Pill(s.aspect.label) { s.sheet = Sheet.ASPECT; touched() }
                Pill(if (s.rotationLocked) "Yön: kilitli" else "Yön: otomatik") { a.toggleRotationLock(); touched() }
                Pill("Küçült") { a.pip() }
                Pill("Kilitle") { s.screenLocked = true; s.controls = false }
                Spacer(Modifier.weight(1f))
                if (s.hasNext) Box(Modifier.size(36.dp).clickable { a.next() }, contentAlignment = Alignment.Center) { Glyph("next", 24) }
            }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, v) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(v) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = v == selected, onClick = { onPick(v) }); Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Kapat") } },
    )
}

@Composable
private fun TracksDialog(s: PlayerState, a: PlayerActions) {
    AlertDialog(
        onDismissRequest = { s.sheet = Sheet.NONE },
        title = { Text("Ses ve altyazı") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Ses", style = MaterialTheme.typography.titleSmall)
                if (s.audioTracks.isEmpty()) Text("Ses parçası bulunamadı.", style = MaterialTheme.typography.bodySmall)
                s.audioTracks.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { a.selectAudio(t) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(t.selected, { a.selectAudio(t) }); Text(t.label)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Altyazı", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().clickable { a.selectText(null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.textOff || s.textTracks.none { it.selected }, { a.selectText(null) }); Text("Kapalı")
                }
                s.textTracks.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { a.selectText(t) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!s.textOff && t.selected, { a.selectText(t) }); Text(t.label)
                    }
                }
                TextButton(onClick = a::pickSubtitleFile) { Text(if (s.externalSub == null) "Altyazı dosyası seç…" else "Başka altyazı dosyası seç…") }
                s.externalSub?.let { name ->
                    Text("Harici: $name", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Zaman kaydırma: " + (if (s.subOffset >= 0) "+" else "") + "%.1f sn".format(s.subOffset / 1000.0),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { a.shiftSubtitle(-500) }) { Text("−0,5") }
                        OutlinedButton(onClick = { a.shiftSubtitle(-100) }) { Text("−0,1") }
                        OutlinedButton(onClick = { a.shiftSubtitle(100) }) { Text("+0,1") }
                        OutlinedButton(onClick = { a.shiftSubtitle(500) }) { Text("+0,5") }
                    }
                    Text("Altyazı konuşmadan önce geliyorsa + yönüne, sonra geliyorsa − yönüne kaydır.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Altyazı görünümü", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubSize.entries.forEach { z -> FilterChip(selected = s.subSize == z, onClick = { a.setSubSize(z) }, label = { Text(z.label) }) }
                }
                Row(Modifier.clickable { a.toggleSubBackground() }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(s.subBackground, { a.toggleSubBackground() }); Text("Arka plan kutusu")
                }
            }
        },
        confirmButton = { TextButton(onClick = { s.sheet = Sheet.NONE }) { Text("Kapat") } },
    )
}
