package app.kinjar.data.receipt

import app.kinjar.domain.Currency
import org.junit.Assert.*
import org.junit.Test

class NbsIpsParserTest {
    private val qr = "K:PR|V:01|C:1|R:200000000000000001|N:ЈП Пример\nНови Сад|I:RSD3018,58|SF:221|S:Uplata: voda|RO:00000138"

    @Test fun `payment details retain Unicode decimals and reference leading zeros`() {
        val slip = NbsIpsParser.parse(qr + "|P:Primer Platilac\r\nPrimer adresa")
        assertEquals("ЈП Пример", slip.merchant)
        assertEquals("ЈП Пример\nНови Сад", slip.recipient)
        assertEquals("200000000000000001", slip.account)
        assertEquals("3018.58", slip.amount.amount.toPlainString())
        assertEquals(Currency.RSD, slip.amount.currency)
        assertEquals("221", slip.paymentCode)
        assertEquals("Uplata: voda", slip.purpose)
        assertEquals("00000138", slip.reference)
        assertEquals("ЈП Пример - Uplata: voda", slip.note)
        assertEquals("Primer Platilac\r\nPrimer adresa", slip.payer)
    }

    @Test fun `optional fields may be absent and NBS fractional formats normalize exactly`() {
        val minimal = qr.substringBefore("|S:").replace("ЈП Пример\nНови Сад", "Čair Žito 365")
        listOf("1," to "1.00", "1,1" to "1.10", "1,01" to "1.01", "0,01" to "0.01").forEach { (input, expected) ->
            val slip = NbsIpsParser.parse(minimal.replace("3018,58", input))
            assertEquals(expected, slip.amount.amount.toPlainString())
            assertEquals("Čair Žito 365", slip.merchant)
            assertEquals("Čair Žito 365", slip.note)
            assertNull(slip.payer)
        }
    }

    @Test fun `malformed payloads cannot silently change payment details`() {
        val invalid = listOf(
            "", " $qr", "$qr|", qr.replace("|V:01", ""), "$qr|I:RSD1,00", "$qr|S:Other",
            qr.replace("200000000000000001", "200-1-01"), qr.replace("SF:221", "SF:999"),
            qr.replace("N:ЈП Пример\nНови Сад", "N:"), qr.replace("Uplata: voda", "a".repeat(36)),
            qr.replace("ЈП Пример\nНови Сад", "a".repeat(71)), qr.replace("ЈП Пример\nНови Сад", "a\nb\nc\nd"),
            qr.replace("Uplata: voda", "a\nb"), qr.replace("ЈП Пример", "bad\u0000name"),
            qr.replace("ЈП Пример", "bad\rname"), qr.replace("RO:00000138", "RO:97ABC"),
            qr.replace("RO:00000138", "RO:0"), "$qr|P:", "x".repeat(1025),
        ) + listOf("0,00", "-1,00", "1.00", "1.000,00", "1,001", "1e3,", ",01", "1,00 ", "1000000000000000,")
            .map { qr.replace("3018,58", it) }
        invalid.forEach { assertThrows(it, IllegalArgumentException::class.java) { NbsIpsParser.parse(it) } }
    }

    @Test fun `unsupported versions encodings currencies types and fields are explicit`() {
        listOf(qr.replace("K:PR", "K:PT"), qr.replace("V:01", "V:02"), qr.replace("C:1", "C:2"),
            qr.replace("I:RSD", "I:EUR"), "$qr|X:value").forEach {
            assertThrows(UnsupportedPaymentSlip::class.java) { NbsIpsParser.parse(it) }
        }
    }
}
