package app.kinjar.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CategoryTransferProtocolTest {
    private val date = LocalDate.of(2026, 11, 9)
    private fun rsd(value: String) = Money.of(value, Currency.RSD)
    private fun eur(value: String) = Money.of(value, Currency.EUR)
    private fun transfer(received: Money? = eur("10"), money: Money = rsd("1170")) =
        Transaction("t", TransactionType.TRANSFER, money, "rsd", "eur", null, date, "", "", "m", TransactionSource.MANUAL, received)
    private fun op(vararg events: Event) = SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "a", installId = "a",
        sequence = 1, parents = emptySet(), resources = mapOf("x:y" to ResourceAccess.WRITE),
        events = events.mapIndexed { i, e -> OperationEvent("a-$i", "a", i + 1L, 1, e) })

    @Test fun `legacy categories and transactions keep their exact event bytes`() {
        assertEquals("""{"type":"CategoryCreated","category":{"id":"c","name":"Food","color":"#3D4BD6","archived":false}}""",
            EventJson.encodeToString(Event.serializer(), CategoryCreated(Category("c", "Food", "#3D4BD6"))))
        assertEquals("""{"type":"TransactionAdded","transaction":{"id":"t","type":"EXPENSE","money":""" +
            EventJson.encodeToString(Money.serializer(), rsd("5")) +
            ""","accountId":"a","toAccountId":null,"categoryId":"c","date":"2026-11-09","note":"","merchant":"","paidBy":"m","source":"MANUAL"},"receipt":null}""",
            EventJson.encodeToString(Event.serializer(), TransactionAdded(
                Transaction("t", TransactionType.EXPENSE, rsd("5"), "a", null, "c", date, "", "", "m", TransactionSource.MANUAL))))
        val legacy = """{"type":"CategoryUpdated","category":{"id":"c","name":"Food","color":"#3D4BD6","archived":false}}"""
        val decoded = EventJson.decodeFromString(Event.serializer(), legacy) as CategoryUpdated
        assertEquals(CategoryKind.EXPENSE, decoded.category.kind)
        assertNull(decoded.category.icon)
        assertEquals(legacy, EventJson.encodeToString(Event.serializer(), decoded))
    }

    @Test fun `new category and transfer fields round trip`() {
        val income = CategoryCreated(Category("i", "Salary", "#2E7D32", kind = CategoryKind.INCOME, icon = "work"))
        val moved = TransactionAdded(transfer())
        listOf<Event>(income, moved).forEach { assertEquals(it, EventJson.decodeFromString(Event.serializer(), EventJson.encodeToString(Event.serializer(), it))) }
        assertTrue(EventJson.encodeToString(Event.serializer(), moved).contains("\"received\""))
    }

    @Test fun `only new data requires operation version 4 and older readers stop before reading it`() {
        assertEquals(1, op(CategoryCreated(Category("c", "Food", "#000000")), CategoryUpdated(Category("c", "Food", "#111111"))).version)
        assertEquals(1, op(TransactionAdded(transfer(received = null, money = eur("10")))).version)
        listOf<Event>(
            CategoryCreated(Category("i", "Salary", "#000000", kind = CategoryKind.INCOME)),
            CategoryUpdated(Category("c", "Food", "#000000", icon = "cart")),
            TransactionAdded(transfer()),
            TransactionEdited(transfer()),
        ).forEach { event ->
            val operation = op(AccountArchived("x"), AccountArchived("y"), event)
            assertEquals(4, operation.version)
            val bytes = OperationCodec.encode(operation)
            assertEquals(operation, OperationCodec.decode(bytes))
            assertArrayEquals(bytes, OperationCodec.encode(OperationCodec.decode(bytes)))
            assertThrows(IllegalArgumentException::class.java) {
                OperationCodec.decode(String(bytes).replace("\"version\":4", "\"version\":3").toByteArray())
            }
            assertThrows(UnsupportedSyncVersion::class.java) {
                OperationCodec.decode(String(bytes).replace("\"version\":4", "\"version\":5").toByteArray())
            }
        }
    }

    @Test fun `received amounts belong to cross currency transfers only`() {
        assertThrows(IllegalArgumentException::class.java) { transfer(received = rsd("1170")) }
        assertThrows(IllegalArgumentException::class.java) { transfer(received = eur("0")) }
        assertThrows(IllegalArgumentException::class.java) {
            Transaction("t", TransactionType.INCOME, rsd("1"), "rsd", null, null, date, "", "", "m", TransactionSource.MANUAL, eur("1"))
        }
    }

    @Test fun `transfers debit the sent amount, credit the received amount and are neither income nor spending`() {
        val accounts = listOf(Account("rsd", "Cash", Currency.RSD), Account("eur", "Euro", Currency.EUR))
        val home = HomeCalculator.summarize(date, accounts, listOf(transfer()), Rates.EMPTY)
        assertEquals(rsd("-1170"), home.balances["rsd"])
        assertEquals(eur("10"), home.balances["eur"])
        assertTrue(home.income.isZero && home.spent.isZero)
    }

    @Test fun `integrity requires the received amount in the destination currency`() {
        val accounts = listOf(AccountCreated(Account("rsd", "Cash", Currency.RSD)), AccountCreated(Account("eur", "Euro", Currency.EUR)))
        FinancialIntegrity().applying(accounts + TransactionAdded(transfer()), strict = true)
        assertThrows(IllegalArgumentException::class.java) {
            FinancialIntegrity().applying(accounts + TransactionAdded(transfer(received = null)), strict = true)
        }
        val sameCurrency = transfer(received = null).copy(toAccountId = "rsd2")
        FinancialIntegrity().applying(accounts + AccountCreated(Account("rsd2", "Bank", Currency.RSD)) + TransactionAdded(sameCurrency), strict = true)
    }
}
