package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CalcTest {
    private fun near(a: Double, b: Double, eps: Double = 0.01) = assertTrue("$a ≈ $b", abs(a - b) < eps)

    @Test fun isbankOrnegi() {
        // İş Bankası örnek ödeme planı: 400.000 TL, 24 ay, %3,4 → 27.374,92 TL taksit
        val l = Calc.loan(400000.0, 3.4, 24, 15.0, 15.0)
        near(l.installment, 27374.92)
        near(l.rows.last().balance, 0.0)
        near(l.rows.sumOf { it.principal }, 400000.0)
    }
    @Test fun vergisiz() { near(Calc.loan(100000.0, 3.0, 12, 0.0, 0.0).installment, 10046.21) }
    @Test fun faizsiz() { near(Calc.loan(1200.0, 0.0, 12, 15.0, 15.0).installment, 100.0) }
    @Test fun kdv() {
        val a = Calc.vat(100.0, 20.0, includes = false); near(a.gross, 120.0); near(a.vat, 20.0)
        val b = Calc.vat(120.0, 20.0, includes = true); near(b.net, 100.0)
    }
    @Test fun sayiOkuma() {
        near(Calc.parse("100.000,50")!!, 100000.5)
        near(Calc.parse("100.000")!!, 100000.0)
        near(Calc.parse("3,4")!!, 3.4)
        near(Calc.parse("3.4")!!, 3.4)
        near(Calc.parse("1 250 TL")!!, 1250.0)
        assertEquals(null, Calc.parse("abc"))
    }
    @Test fun wifi() {
        val w = QrText.Wifi("Ev;Ağı", "şi:fre", QrText.Security.WPA)
        val s = QrText.wifi(w)
        assertEquals("WIFI:T:WPA;S:Ev\\;Ağı;P:şi\\:fre;;", s)
        assertEquals(w, QrText.parseWifi(s))
        assertEquals(QrText.Security.NONE, QrText.parseWifi("WIFI:T:nopass;S:Misafir;;")!!.security)
    }
}
