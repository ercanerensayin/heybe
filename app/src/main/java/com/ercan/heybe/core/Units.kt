package com.ercan.heybe.core

/** Birim dönüştürme: her birim temel birime çarpanla bağlıdır (sıcaklık hariç). */
object Units {
    data class Unit(val id: String, val label: String, val factor: Double)
    data class Group(val id: String, val label: String, val units: List<Unit>)

    val groups = listOf(
        Group("uzunluk", "Uzunluk", listOf(
            Unit("mm", "Milimetre", 0.001), Unit("cm", "Santimetre", 0.01), Unit("m", "Metre", 1.0), Unit("km", "Kilometre", 1000.0),
            Unit("in", "İnç", 0.0254), Unit("ft", "Fit", 0.3048), Unit("yd", "Yarda", 0.9144), Unit("mi", "Mil", 1609.344), Unit("nmi", "Deniz mili", 1852.0))),
        Group("agirlik", "Ağırlık", listOf(
            Unit("mg", "Miligram", 1e-6), Unit("g", "Gram", 0.001), Unit("kg", "Kilogram", 1.0), Unit("t", "Ton", 1000.0),
            Unit("oz", "Ons", 0.028349523125), Unit("lb", "Libre (pound)", 0.45359237))),
        Group("sicaklik", "Sıcaklık", listOf(Unit("c", "Santigrat (°C)", 1.0), Unit("f", "Fahrenhayt (°F)", 1.0), Unit("k", "Kelvin (K)", 1.0))),
        Group("alan", "Alan", listOf(
            Unit("m2", "Metrekare", 1.0), Unit("km2", "Kilometrekare", 1e6), Unit("donum", "Dönüm", 1000.0), Unit("ha", "Hektar", 10000.0),
            Unit("ft2", "Fitkare", 0.09290304), Unit("acre", "Akre (acre)", 4046.8564224))),
        Group("hacim", "Hacim", listOf(
            Unit("ml", "Mililitre", 0.001), Unit("l", "Litre", 1.0), Unit("m3", "Metreküp", 1000.0),
            Unit("galus", "Galon (ABD)", 3.785411784), Unit("galuk", "Galon (İngiliz)", 4.54609), Unit("bardak", "Su bardağı (200 ml)", 0.2))),
        Group("hiz", "Hız", listOf(
            Unit("kmh", "km/sa", 1 / 3.6), Unit("ms", "m/sn", 1.0), Unit("mph", "mil/sa", 0.44704), Unit("knot", "Knot", 1852.0 / 3600))),
        Group("veri", "Veri", listOf(
            Unit("b", "Bayt", 1.0), Unit("kb", "KB", 1024.0), Unit("mb", "MB", 1024.0 * 1024), Unit("gb", "GB", 1024.0 * 1024 * 1024), Unit("tb", "TB", 1024.0 * 1024 * 1024 * 1024))),
        Group("zaman", "Zaman", listOf(
            Unit("sn", "Saniye", 1.0), Unit("dk", "Dakika", 60.0), Unit("sa", "Saat", 3600.0), Unit("gun", "Gün", 86400.0),
            Unit("hafta", "Hafta", 604800.0), Unit("yil", "Yıl (365 gün)", 31_536_000.0))),
    )

    fun convert(group: String, from: String, to: String, value: Double): Double {
        if (group == "sicaklik") {
            val c = when (from) { "f" -> (value - 32) * 5 / 9; "k" -> value - 273.15; else -> value }
            return when (to) { "f" -> c * 9 / 5 + 32; "k" -> c + 273.15; else -> c }
        }
        val g = groups.first { it.id == group }
        val f = g.units.first { it.id == from }.factor
        val t = g.units.first { it.id == to }.factor
        return value * f / t
    }

    /** Türkçe gösterim: gereksiz sıfırsız, en fazla 6 anlamlı ondalık. */
    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val nf = java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("tr-TR"))
        nf.maximumFractionDigits = if (kotlin.math.abs(v) >= 1000) 2 else 6
        return nf.format(v)
    }
}

/** TCMB "today.xml" kurları. */
object Tcmb {
    data class Rate(val code: String, val name: String, val unit: Int, val buying: Double?, val selling: Double?)
    data class Rates(val date: String, val rates: List<Rate>)

    fun parse(xml: String): Rates {
        val date = Regex("Tarih=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) ?: ""
        val rates = Regex("<Currency[^>]*CurrencyCode=\"([A-Z]{3})\"[^>]*>(.*?)</Currency>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { m ->
            val body = m.groupValues[2]
            fun tag(t: String) = Regex("<$t>([^<]*)</$t>").find(body)?.groupValues?.get(1)?.trim()
            Rate(m.groupValues[1], tag("Isim") ?: m.groupValues[1], tag("Unit")?.toIntOrNull() ?: 1,
                tag("ForexBuying")?.toDoubleOrNull(), tag("ForexSelling")?.toDoubleOrNull())
        }.filter { it.selling != null || it.buying != null }.toList()
        return Rates(date, rates)
    }

    /** amount kadar "from" para birimini "to"ya çevirir (TRY dahil). Döviz satış kuru kullanılır. */
    fun convert(r: Rates, from: String, to: String, amount: Double): Double? {
        fun tryPer(code: String): Double? = if (code == "TRY") 1.0 else r.rates.firstOrNull { it.code == code }?.let { (it.selling ?: it.buying)?.div(it.unit) }
        val a = tryPer(from) ?: return null
        val b = tryPer(to) ?: return null
        return amount * a / b
    }
}
