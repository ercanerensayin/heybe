package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ExtrasTest {
    private fun near(a: Double, b: Double, e: Double = 1e-6) = assertTrue("$a ≈ $b", abs(a - b) < e)

    @Test fun birimler() {
        near(Units.convert("uzunluk", "mi", "km", 1.0), 1.609344)
        near(Units.convert("sicaklik", "c", "f", 100.0), 212.0)
        near(Units.convert("sicaklik", "f", "k", 32.0), 273.15)
        near(Units.convert("alan", "donum", "m2", 2.5), 2500.0)
        near(Units.convert("veri", "gb", "mb", 1.5), 1536.0)
        assertEquals("1.234,57", Units.format(1234.5678))
    }

    private val xml = """<?xml version="1.0" encoding="UTF-8"?><Tarih_Date Tarih="19.11.2026" Date="11/19/2026" Bulten_No="2026/220">
<Currency CrossOrder="0" Kod="USD" CurrencyCode="USD"><Unit>1</Unit><Isim>ABD DOLARI</Isim><ForexBuying>40.1000</ForexBuying><ForexSelling>40.2000</ForexSelling></Currency>
<Currency CrossOrder="9" Kod="JPY" CurrencyCode="JPY"><Unit>100</Unit><Isim>JAPON YENİ</Isim><ForexBuying>26.0</ForexBuying><ForexSelling>26.5</ForexSelling></Currency>
<Currency CrossOrder="1" Kod="EUR" CurrencyCode="EUR"><Unit>1</Unit><Isim>EURO</Isim><ForexBuying>46.0</ForexBuying><ForexSelling>46.2</ForexSelling></Currency>
</Tarih_Date>"""

    @Test fun tcmb() {
        val r = Tcmb.parse(xml)
        assertEquals("19.11.2026", r.date); assertEquals(3, r.rates.size)
        near(Tcmb.convert(r, "USD", "TRY", 10.0)!!, 402.0)
        near(Tcmb.convert(r, "TRY", "JPY", 265.0)!!, 1000.0)
        near(Tcmb.convert(r, "EUR", "USD", 1.0)!!, 46.2 / 40.2)
        assertEquals(null, Tcmb.convert(r, "XYZ", "TRY", 1.0))
    }

    @Test fun csv() {
        val rows = Sheets.parseCsv("Ad;Tutar;Not\r\nAli;1.250,50;\"Çay; şeker\"\r\nVeli;3;\"tırnak \"\"x\"\"\"\r\n")
        assertEquals(listOf("Ali", "1.250,50", "Çay; şeker"), rows[1])
        assertEquals("tırnak \"x\"", rows[2][2])
        assertEquals(';', Sheets.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals(',', Sheets.detectDelimiter("a,b,c\n1,2,3"))
        assertEquals(rows, Sheets.parseCsv(Sheets.writeCsv(rows)))
    }

    @Test fun sayi() {
        assertEquals(1250.5, Sheets.numberOf("1.250,50")!!, 1e-9)
        assertEquals(3.0, Sheets.numberOf("3")!!, 1e-9)
        assertEquals(null, Sheets.numberOf("05321234567"))
        assertEquals(null, Sheets.numberOf("abc"))
    }

    @Test fun xlsxGidisDonus() {
        val rows = listOf(listOf("Ad", "Tutar", "Not"), listOf("Ali", "1.250,50", "Çay & <şeker>"), listOf("", "3", ""))
        val f = File.createTempFile("heybe", ".xlsx"); f.outputStream().use { Sheets.writeXlsx("Liste", rows, it) }
        File("/tmp/heybe_test.xlsx").writeBytes(f.readBytes())
        val s = Sheets.readXlsx(f).single()
        assertEquals("Liste", s.name)
        assertEquals(listOf("Ad", "Tutar", "Not"), s.rows[0])
        assertEquals(listOf("Ali", "1250.5", "Çay & <şeker>"), s.rows[1])
        assertEquals(listOf("", "3.0"), s.rows[2])
    }
}
