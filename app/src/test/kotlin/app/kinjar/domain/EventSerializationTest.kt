package app.kinjar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class EventSerializationTest {
    @Test fun `receipt decimals reject expansion on decode and encode`() {
        for (value in listOf("1E+3000000", "1E-3000000", "0E-2147483647", "9".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) {
                EventJson.decodeFromString(BigDecimalSerializer, "\"$value\"")
            }
            assertThrows(IllegalArgumentException::class.java) {
                EventJson.encodeToString(BigDecimalSerializer, java.math.BigDecimal(value))
            }
        }
        for (value in listOf("0.345", "1E+3", "-1.2500")) {
            val decimal = java.math.BigDecimal(value)
            assertEquals(decimal.toPlainString(), EventJson.decodeFromString(BigDecimalSerializer,
                EventJson.encodeToString(BigDecimalSerializer, decimal)).toPlainString())
        }
    }

    @Test fun `recurring history rejects extreme dates but accepts calendar boundaries`() {
        val event: Event = RecurringRuleSet(RecurringRule("r", "Rent", Money.of("100", Currency.RSD), "a", "c",
            Cadence.MONTHLY, LocalDate.of(2026, 10, 5)))
        val json = EventJson.encodeToString(Event.serializer(), event)
        for (date in listOf("-999999999-01-01", "+999999999-12-31", "1899-12-31", "2101-01-01")) {
            assertThrows(IllegalArgumentException::class.java) {
                EventJson.decodeFromString(Event.serializer(), json.replace("2026-10-05", date))
            }
        }
        for (date in listOf("1900-01-01", "2100-12-31")) {
            val decoded = EventJson.decodeFromString(Event.serializer(), json.replace("2026-10-05", date)) as RecurringRuleSet
            assertEquals(LocalDate.parse(date), decoded.rule.firstDue)
        }
    }

    @Test
    fun `transaction added round trips through json`() {
        val event: Event = TransactionAdded(
            transaction = Transaction(
                id = "t1",
                type = TransactionType.EXPENSE,
                money = Money.of("4860", Currency.RSD),
                accountId = "a1",
                toAccountId = null,
                categoryId = "c1",
                date = LocalDate.of(2026, 10, 14),
                note = "",
                merchant = "Maxi",
                paidBy = "m1",
                source = TransactionSource.MANUAL,
            )
        )
        val json = EventJson.encodeToString(Event.serializer(), event)
        assertEquals(event, EventJson.decodeFromString(Event.serializer(), json))
    }

    @Test
    fun `legacy account kind and goal contribution still decode, new goals default to zero contribution`() {
        val account = """{"type":"AccountCreated","account":{"id":"a","name":"Savings","currency":"EUR","kind":"SAVINGS","archived":false}}"""
        assertEquals(AccountKind.SAVINGS, (EventJson.decodeFromString(Event.serializer(), account) as AccountCreated).account.kind)
        val goal = """{"type":"GoalSet","goal":{"id":"g","name":"Trip","target":{"amount":"3000.00","currency":"EUR"},"monthly":{"amount":"150.00","currency":"EUR"}}}"""
        assertEquals(Money.of("150", Currency.EUR), (EventJson.decodeFromString(Event.serializer(), goal) as GoalSet).goal.monthly)
        assertEquals(Money.zero(Currency.EUR), Goal("g", "Trip", Money.of("3000", Currency.EUR)).monthly)
    }

    @Test
    fun `budget set round trips`() {
        val event: Event = BudgetSet(Budget("cat-groceries", java.time.YearMonth.of(2026, 10), Money.of("60000", Currency.RSD)))
        val json = EventJson.encodeToString(Event.serializer(), event)
        assertEquals(event, EventJson.decodeFromString(Event.serializer(), json))
    }

    @Test
    fun `recurring events round trip with a type discriminator`() {
        val rule = RecurringRule("r1", "Rent", Money.of("40000", Currency.RSD), "a1", "cat-bills", Cadence.MONTHLY, LocalDate.of(2026, 10, 5))
        listOf<Event>(RecurringRuleSet(rule), RecurringRuleArchived("r1"), BillSkipped("r1", "2026-10")).forEach { event ->
            val json = EventJson.encodeToString(Event.serializer(), event)
            assertEquals(event, EventJson.decodeFromString(Event.serializer(), json))
        }
        assertEquals("BillSkipped", EventJson.parseToJsonElement(EventJson.encodeToString(Event.serializer(), BillSkipped("r1", "2026")))
            .let { it as kotlinx.serialization.json.JsonObject }["type"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `goal events round trip`() {
        val goal = Goal("g1", "Trip", Money.of("3000", Currency.EUR), Money.of("150", Currency.EUR))
        val allocation = GoalAllocation("a1", "g1", Money.of("200", Currency.EUR), LocalDate.of(2026, 10, 14))
        listOf<Event>(
            GoalSet(goal), GoalArchived("g1"), GoalAllocated(allocation), GoalAllocationDeleted("a1"),
            GoalsReordered(listOf("g2", "g1")), AccountsReordered(listOf("a2", "a1")),
        ).forEach { event ->
            val json = EventJson.encodeToString(Event.serializer(), event)
            assertEquals(event, EventJson.decodeFromString(Event.serializer(), json))
        }
    }

    @Test
    fun `money serializes as plain string amount`() {
        val json = EventJson.encodeToString(Money.serializer(), Money.of("12.5", Currency.EUR))
        assertEquals("""{"amount":"12.50","currency":"EUR"}""", json)
    }
}
