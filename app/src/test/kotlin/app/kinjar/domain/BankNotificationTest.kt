package app.kinjar.domain

import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankNotificationTest {
    @Test
    fun `Raiffeisen card balance inherits payment currency and keeps original precision`() {
        val body = """
            Koriscenje kartice 0000**0000
            Datum: 26.11.2026 00:23:13
            Iznos: 1.250,00 RSD
            Mesto: MERCHANT BEOGRAD RS
            Stanje: 821,63941
        """.trimIndent()
        val parsed = BankNotificationParser.parse(BankApp.RAIFFEISEN, "Raiffeisen banka", body)
        assertTrue(BankNotificationParser.shouldCapture("Raiffeisen banka", body))
        assertEquals(TransactionType.EXPENSE, parsed.direction)
        assertEquals(Money.of("1250", Currency.RSD), parsed.money)
        assertEquals(LocalDateTime.of(2026, 11, 26, 0, 23, 13), parsed.transactionTime)
        assertEquals(ReportedBankBalance(BigDecimal("821.63941"), Currency.RSD, available = false), parsed.balances.single())
    }

    @Test
    fun `Raiffeisen incoming payment is distinct from purchases`() {
        val parsed = BankNotificationParser.parse(BankApp.RAIFFEISEN, "Raiffeisen banka", """
            Imate uplatu po računu 0000**0000 za PRIMALAC
            Platilac: POŠILJALAC
            Iznos:11.617,60 RSD
            Datum: 26.11.2026
        """.trimIndent())
        assertEquals(TransactionType.INCOME, parsed.direction)
        assertEquals("POŠILJALAC", parsed.counterparty)
        assertEquals(Money.of("11617.60", Currency.RSD), parsed.money)
        assertNull(parsed.transactionTime)
    }

    @Test
    fun `Yettel purchase and balances use different decimal conventions`() {
        val parsed = BankNotificationParser.parse(BankApp.YETTEL, "Transakcija je izvršena", """
            Karticom 0000****0000, iznos: 4,237.46 RSD,
            26.11.2026, MERCHANT 205 RUMA RUMA .
            Trenutno raspolozivo: 30.201,82 RSD 0,00
            EUR .
        """.trimIndent())
        assertEquals(TransactionType.EXPENSE, parsed.direction)
        assertEquals(Money.of("4237.46", Currency.RSD), parsed.money)
        assertEquals("MERCHANT 205 RUMA RUMA", parsed.counterparty)
        assertEquals(listOf(
            ReportedBankBalance(BigDecimal("30201.82"), Currency.RSD, available = true),
            ReportedBankBalance(BigDecimal("0.00"), Currency.EUR, available = true),
        ), parsed.balances)
        assertNull(parsed.transactionTime)
    }

    @Test
    fun `unknown transfer remains reviewable and authentication is ignored`() {
        val transfer = "Prenos sredstava: Iznos: 100,00 EUR"
        assertTrue(BankNotificationParser.shouldCapture("", transfer))
        assertNull(BankNotificationParser.parse(BankApp.YETTEL, "", transfer).direction)
        assertFalse(BankNotificationParser.shouldCapture("OTP", "Potvrdite transakciju. Iznos: 100,00 EUR"))
        val cash = BankNotificationParser.parse(BankApp.RAIFFEISEN, "", "Mesto: ATM CENTAR\nIznos: 100,00 EUR")
        assertEquals(TransactionType.TRANSFER, cash.direction)
    }
}
