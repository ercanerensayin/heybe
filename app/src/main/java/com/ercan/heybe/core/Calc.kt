package com.ercan.heybe.core

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.pow

/** Kredi ve KDV hesapları (Android'den bağımsız). */
object Calc {
    private val TR = Locale.forLanguageTag("tr-TR")

    enum class LoanType(val label: String, val kkdf: Double, val bsmv: Double) {
        IHTIYAC("İhtiyaç", 15.0, 15.0),
        TASIT("Taşıt", 15.0, 15.0),
        KONUT("Konut", 0.0, 0.0),
    }

    data class Row(val no: Int, val installment: Double, val principal: Double, val interest: Double,
                   val kkdf: Double, val bsmv: Double, val balance: Double)

    data class Loan(val installment: Double, val total: Double, val totalInterest: Double,
                    val totalKkdf: Double, val totalBsmv: Double, val rows: List<Row>)

    /** Aylık faiz (%), vergiler faiz üzerinden (%). Eşit taksitli ödeme planı. */
    fun loan(amount: Double, monthlyRatePct: Double, months: Int, kkdfPct: Double, bsmvPct: Double): Loan {
        require(amount > 0) { "Tutar sıfırdan büyük olmalı." }
        require(months in 1..480) { "Vade 1 ile 480 ay arasında olmalı." }
        require(monthlyRatePct >= 0) { "Faiz negatif olamaz." }
        val r = monthlyRatePct / 100
        val k = kkdfPct / 100; val b = bsmvPct / 100
        val re = r * (1 + k + b)
        val inst = if (re == 0.0) amount / months else amount * re * (1 + re).pow(months) / ((1 + re).pow(months) - 1)
        var bal = amount
        val rows = (1..months).map { i ->
            val interest = bal * r
            val kk = interest * k; val bs = interest * b
            var principal = inst - interest - kk - bs
            if (i == months) principal = bal
            bal = (bal - principal).coerceAtLeast(0.0)
            Row(i, inst, principal, interest, kk, bs, bal)
        }
        return Loan(inst, inst * months, rows.sumOf { it.interest }, rows.sumOf { it.kkdf }, rows.sumOf { it.bsmv }, rows)
    }

    data class Vat(val net: Double, val vat: Double, val gross: Double)

    /** includes = tutar KDV dahil mi? */
    fun vat(amount: Double, ratePct: Double, includes: Boolean): Vat {
        val f = ratePct / 100
        return if (includes) { val net = amount / (1 + f); Vat(net, amount - net, amount) }
        else Vat(amount, amount * f, amount * (1 + f))
    }

    /** "100.000,50" / "100000.5" / "1 250" gibi girişleri sayıya çevirir. */
    fun parse(text: String): Double? {
        var s = text.trim().replace(" ", "").replace("₺", "").replace("TL", "", ignoreCase = true).replace("%", "")
        if (s.isEmpty()) return null
        s = when {
            ',' in s -> s.replace(".", "").replace(',', '.')
            s.count { it == '.' } > 1 -> s.replace(".", "")
            Regex("^\\d{1,3}\\.\\d{3}$").matches(s) -> s.replace(".", "")
            else -> s
        }
        return s.toDoubleOrNull()
    }

    fun money(v: Double): String = NumberFormat.getNumberInstance(TR).apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }.format(v) + " TL"
}
