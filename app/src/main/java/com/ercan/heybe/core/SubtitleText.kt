package com.ercan.heybe.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Altyazı dosyası: kodlama tanıma (UTF-8 / Windows-1254) ve zaman kaydırma. */
object SubtitleText {
    enum class Format(val ext: String) { SRT("srt"), VTT("vtt"), ASS("ass") }

    fun formatOf(name: String): Format? = when (name.substringAfterLast('.', "").lowercase()) {
        "srt" -> Format.SRT; "vtt" -> Format.VTT; "ass", "ssa" -> Format.ASS; else -> null
    }

    /** UTF-8 (BOM'lu ya da BOM'suz) geçerliyse UTF-8, değilse Türkçe Windows-1254 olarak çözer. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).removePrefix("﻿")
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).removePrefix("﻿")
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1254"))
        }
    }

    private val srtTime = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")
    private val assTime = Regex("(\\d):(\\d{2}):(\\d{2})\\.(\\d{2})")

    /** Bütün zaman damgalarını ms kadar kaydırır (negatifse sıfırın altına inmez). */
    fun shift(text: String, ms: Long, format: Format): String {
        if (ms == 0L) return text
        return when (format) {
            Format.SRT, Format.VTT -> text.lines().joinToString("\n") { line ->
                if (!line.contains("-->")) line
                else srtTime.replace(line) { m ->
                    val (h, mi, s, f) = m.destructured
                    val sep = if (m.value.contains(',')) ',' else '.'
                    fmtSrt(toMs(h.toLong(), mi.toLong(), s.toLong(), f.toLong()) + ms, sep)
                }
            }
            Format.ASS -> text.lines().joinToString("\n") { line ->
                if (!line.startsWith("Dialogue:", ignoreCase = true)) line
                else assTime.replace(line) { m ->
                    val (h, mi, s, cs) = m.destructured
                    fmtAss(toMs(h.toLong(), mi.toLong(), s.toLong(), cs.toLong() * 10) + ms)
                }
            }
        }
    }

    private fun toMs(h: Long, m: Long, s: Long, ms: Long) = ((h * 60 + m) * 60 + s) * 1000 + ms

    private fun fmtSrt(t0: Long, sep: Char): String {
        val t = t0.coerceAtLeast(0)
        return "%02d:%02d:%02d%c%03d".format(t / 3600000, t / 60000 % 60, t / 1000 % 60, sep, t % 1000)
    }

    private fun fmtAss(t0: Long): String {
        val t = t0.coerceAtLeast(0)
        return "%d:%02d:%02d.%02d".format(t / 3600000, t / 60000 % 60, t / 1000 % 60, t % 1000 / 10)
    }
}

/** Kaldığı yer ve izlendi kuralları. */
object PlaybackRules {
    const val MIN_RESUME_MS = 5_000L

    fun isWatched(position: Long, duration: Long): Boolean = duration > 0 && position >= duration * 9 / 10

    fun resumeAt(saved: Long, duration: Long, watched: Boolean): Long =
        if (watched || saved < MIN_RESUME_MS || (duration > 0 && saved >= duration - 3_000)) 0L else saved

    fun progress(position: Long, duration: Long): Float =
        if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
