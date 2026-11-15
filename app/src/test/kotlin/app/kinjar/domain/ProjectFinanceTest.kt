package app.kinjar.domain

import app.kinjar.ui.projects.signedProjectMoney
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ProjectFinanceTest {
    private fun money(value: String) = Money.of(value, Currency.RSD)
    private val day = LocalDate.of(2026, 11, 12)
    private val config = ProjectReimbursement("Dad", money("-1471"), day.withDayOfMonth(1))
    private val cost = ProjectCost("bill", "dad", null, money("12543"), money("12543"), day, "Bills", unpaid = true)
    private fun paid(value: String) = ProjectRepayment("receipt", ProjectPayment(money(value), money(value), day))

    @Test fun `monthly bills preserve original values while exact less and more repayments carry`() {
        val month = YearMonth.from(day)
        fun balance(value: String) = projectMonthBalance(config, listOf(cost), listOf(paid(value)), month)
        assertEquals(money("0"), balance("11072").closing)
        assertEquals(money("1072"), balance("10000").closing)
        assertEquals(money("-7468"), balance("18540").closing)
        assertEquals(money("0"), balance("11072").laterApplied(money("1000")))
        assertEquals(money("1072"), balance("10000").laterApplied(money("2000")))
        assertEquals(money("0"), balance("18540").laterApplied(money("2000")))
        val december = projectMonthBalance(config, listOf(cost), listOf(paid("18540")), month.plusMonths(1))
        assertFalse(december.hasBills)
        assertEquals(money("-7468"), december.opening)
        val next = cost.copy(id = "dec", date = day.plusMonths(1), original = money("10000"), converted = money("10000"))
        assertEquals(money("2532"), projectMonthBalance(config, listOf(cost, next), listOf(paid("18540")), month.plusMonths(1)).closing)
        assertEquals(money("12543"), cost.converted)
        assertTrue(cost.unpaid)
    }

    @Test fun `opening balance accepts signed comma amounts`() {
        assertEquals(money("-1471.25"), signedProjectMoney("-1471,25", Currency.RSD))
        assertEquals(money("1471"), signedProjectMoney("+1471", Currency.RSD))
        assertNull(signedProjectMoney("abc", Currency.RSD))
    }

    @Test fun `allocations must match payment exactly and cannot repeat a cost`() {
        val payment = ProjectPayment(money("900"), money("900"), day, "expense")
        val settlement = ProjectSettlement("s", payment, mapOf("a" to money("550"), "b" to money("350")))
        assertThrows(IllegalArgumentException::class.java) { settlement.copy(type = TransactionType.TRANSFER) }
        assertThrows(IllegalArgumentException::class.java) { settlement.copy(allocations = mapOf("a" to money("600"), "b" to money("400"))) }
        assertThrows(IllegalArgumentException::class.java) { settlement.copy(allocations = mapOf("a" to money("900"), "b" to money("0"))) }
        assertThrows(IllegalArgumentException::class.java) { ProjectFinance("dad", settlements = listOf(settlement, settlement.copy(id = "other"))) }
    }

    @Test fun `linked payment limit preserves room for sync resources`() {
        val receipts = (1..401).map { index -> paid("1").copy(id = "receipt-$index", payment = paid("1").payment.copy(transactionId = "income-$index")) }
        assertEquals(400, ProjectFinance("dad", config, repayments = receipts.take(400)).payments().size)
        assertThrows(IllegalArgumentException::class.java) { ProjectFinance("dad", config, repayments = receipts) }
    }

    @Test fun `income differences preserve bill amounts and require version seven`() {
        for (received in listOf("18000", "7000", "8000")) {
            val settlement = ProjectSettlement("income", ProjectPayment(money(received), money(received), day, "tx"),
                mapOf("a" to money("5000"), "b" to money("3000")), TransactionType.INCOME)
            assertEquals(money("8000"), settlement.allocated)
            val event = ProjectFinanceSaved(ProjectFinance("dad", settlements = listOf(settlement)))
            val operation = SyncOperation("family", "dataset", "user", "device", 1, emptySet(), mapOf("project:dad" to ResourceAccess.WRITE),
                listOf(OperationEvent("event", "device", 1, 1, event)))
            assertEquals(if (received == "8000") 6 else 7, operation.version)
            assertEquals(operation, OperationCodec.decode(OperationCodec.encode(operation)))
        }
    }

    @Test fun `new finance events require version six while existing projects stay version five`() {
        val event = ProjectFinanceSaved(ProjectFinance("dad", config))
        val operation = SyncOperation("family", "dataset", "user", "device", 1, emptySet(), mapOf("project:dad" to ResourceAccess.WRITE),
            listOf(OperationEvent("event", "device", 1, 1, event)))
        assertEquals(6, operation.version)
        assertEquals(operation, OperationCodec.decode(OperationCodec.encode(operation)))
    }
}
