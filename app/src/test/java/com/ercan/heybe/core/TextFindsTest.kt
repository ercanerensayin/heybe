package com.ercan.heybe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFindsTest {
    // Geçerli örnek IBAN (doğrulama basamakları hesaplanmış)
    private val iban = "TR330006100519786457841326"
    @Test fun ibanBoslukluBulunur() {
        assertEquals(listOf("TR33 0006 1005 1978 6457 8413 26"), TextFinds.ibans("Hesap: TR33 0006 1005 1978 6457 8413 26 Ad Soyad"))
    }
    @Test fun ibanHatalisiElenir() { assertTrue(TextFinds.ibans("TR330006100519786457841327").isEmpty()) }
    @Test fun ibanDogrulama() { assertTrue(TextFinds.validIban(iban)) }
    @Test fun telefon() {
        assertEquals(listOf("0532 123 45 67", "0212 555 66 77"), TextFinds.phones("Tel: +90 532 123 45 67, sabit (0212) 555-66-77"))
    }
    @Test fun eposta() { assertEquals(listOf("ali.veli@ornek.com.tr"), TextFinds.emails("E-posta: Ali.Veli@ornek.com.tr.")) }
    @Test fun link() { assertEquals(listOf("https://turkiye.gov.tr/x"), TextFinds.links("Bkz. https://turkiye.gov.tr/x.")) }
}
