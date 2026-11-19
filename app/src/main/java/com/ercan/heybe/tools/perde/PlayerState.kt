package com.ercan.heybe.tools.perde

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.TrackGroup
import androidx.media3.ui.AspectRatioFrameLayout

data class TrackOption(val label: String, val group: TrackGroup?, val index: Int, val language: String?, val selected: Boolean)

enum class Aspect(val label: String, val mode: Int) {
    FIT("Sığdır", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Doldur", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Yakınlaştır", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
}

enum class SubSize(val label: String, val fraction: Float) { KUCUK("Küçük", 0.045f), ORTA("Orta", 0.058f), BUYUK("Büyük", 0.075f) }

enum class Sheet { NONE, TRACKS, SPEED, ASPECT }

/** Oynatma ekranının Compose tarafından okunan durumu. */
class PlayerState {
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var controls by mutableStateOf(true)
    var screenLocked by mutableStateOf(false)
    var rotationLocked by mutableStateOf(false)
    var aspect by mutableStateOf(Aspect.FIT)
    var speed by mutableFloatStateOf(1f)
    var resumedFrom by mutableLongStateOf(0L)
    var countdown by mutableStateOf<Int?>(null)
    var nextTitle by mutableStateOf<String?>(null)
    var hasNext by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var subSize by mutableStateOf(SubSize.ORTA)
    var subBackground by mutableStateOf(true)
    var subOffset by mutableLongStateOf(0L)
    var externalSub by mutableStateOf<String?>(null)
    var audioTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var textTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var textOff by mutableStateOf(false)
    var sheet by mutableStateOf(Sheet.NONE)
    var error by mutableStateOf<String?>(null)
    var seekFlash by mutableIntStateOf(0)
    var seekFlashSeq by mutableIntStateOf(0)
}
