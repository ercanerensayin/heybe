package com.ercan.heybe.core

/** Medya araçları için saf hesaplar. */
object MediaMath {
    /** FFmpeg ilerleme satırından süreyi okur: "... time=00:01:02.50 ..." → 62500 ms. */
    fun ffmpegTime(line: String): Long? {
        val m = Regex("time=(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?").find(line) ?: return null
        val (h, mi, s, f) = m.destructured
        val frac = if (f.isEmpty()) 0 else f.padEnd(3, '0').take(3).toLong()
        return ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + frac
    }

    /** ffmpeg -ss/-to için "SS:DD:SS.mmm". */
    fun stamp(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d.%03d".format(t / 3600000, t / 60000 % 60, t / 1000 % 60, t % 1000)
    }

    /** Hedef boyut tahmini (MB): (video+ses bit hızı) × süre. */
    fun estimateMb(durationMs: Long, videoBps: Int, audioBps: Int = 128_000): Double =
        (videoBps + audioBps).toDouble() * durationMs / 1000 / 8 / 1_048_576

    /** Kısa kenarı hedefe indirir; kaynak zaten küçükse dokunmaz (büyütme yok). */
    fun shouldScale(srcShortSide: Int, targetShortSide: Int): Boolean = srcShortSide > targetShortSide
}
