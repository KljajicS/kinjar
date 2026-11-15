package app.kinjar.data

import app.kinjar.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProjectFinanceRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-14T12:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "local", clock, Projector(db))
    private val repo = ProjectRepository(db, store, clock)
    private fun eur(value: String) = Money.of(value, Currency.EUR)
    private val project = Project("house", "House", sourceCategoryIds = setOf("bills"))
    private val tx = Transaction("expense", TransactionType.EXPENSE, eur("900"), "bank", null, "bills", clock.today(), "", "", "member", TransactionSource.MANUAL)
    private val a = ProjectCost("a", "house", null, eur("600"), eur("600"), clock.today(), "Materials", unpaid = true)
    private val b = a.copy(id = "b", original = eur("400"), converted = eur("400"), note = "Labour")
    private val base = ProjectFinance("house")
    private val settlement = ProjectSettlement("settlement", ProjectPayment(tx.money, tx.money, tx.date, tx.id), mapOf("a" to eur("550"), "b" to eur("350")))
    private val paid = base.copy(settlements = listOf(settlement))

    private suspend fun seed(sync: Boolean = false) {
        store.appendAll(listOf(AccountCreated(Account("bank", "Bank", Currency.EUR)), CategoryCreated(Category("bills", "Bills", "#123456")),
            TransactionAdded(tx), ProjectSaved(project), ProjectCostSaved(a), ProjectCostSaved(b)))
        if (sync) {
            val history = store.snapshot()
            store.activate("user", "family", "dataset", "member", 1, history, HistoryCodec.digest(HistoryCodec.encode(history)), true)
        }
    }

    @Test fun `bulk settlement persists original bills and effective paid amounts and undo restores unpaid`() = runTest {
        seed()
        repo.saveFinance(paid, base, listOf(a, b))
        assertEquals(listOf(a, b), repo.readBook().costs)
        assertEquals(listOf(eur("550"), eur("350")), repo.readBook().costsFor("house").map { it.converted })
        assertTrue(repo.readBook().costsFor("house").none { it.unpaid })
        store.rebuild()
        assertEquals(paid, repo.readBook().finance("house"))
        assertEquals(listOf(tx), repo.readBook().transactions)
        repo.saveFinance(base, paid)
        assertEquals(listOf(a, b), repo.readBook().costsFor("house"))
    }

    @Test fun `settled bills cannot be edited removed or linked twice and stale writes fail`() = runTest {
        seed()
        repo.saveFinance(paid, base, listOf(a, b))
        assertTrue(runCatching { repo.record(listOf(a.copy(note = "Changed")), listOf(a)) }.isFailure)
        assertTrue(runCatching { repo.removeCost(a.id, a) }.isFailure)
        assertTrue(runCatching { repo.record(listOf(a.copy(id = "duplicate", original = tx.money, converted = tx.money, transactionId = tx.id, unpaid = false))) }.isFailure)
        assertTrue(runCatching { repo.saveFinance(base, base) }.isFailure)
        assertEquals(paid, repo.readBook().finance("house"))
    }

    @Test fun `changed and deleted expense require review and deletion restores unpaid display`() = runTest {
        seed()
        repo.saveFinance(paid, base, listOf(a, b))
        store.append(TransactionEdited(tx.copy(money = eur("850"))))
        assertTrue(repo.readBook().needsReview(a))
        assertEquals(eur("550"), repo.readBook().costsFor("house").first().converted)
        store.append(TransactionDeleted(tx.id))
        assertTrue(repo.readBook().costsFor("house").all { it.unpaid })
        repo.saveFinance(base, paid)
        assertEquals(base, repo.readBook().finance("house"))
    }

    @Test fun `repayments retain signed balance and never mutate account transactions`() = runTest {
        seed()
        val config = ProjectReimbursement("Dad", eur("-100"), tx.date.withDayOfMonth(1))
        val receipt = ProjectRepayment("cash", ProjectPayment(eur("1000"), eur("1000"), tx.date))
        val finance = base.copy(reimbursement = config, repayments = listOf(receipt))
        repo.saveFinance(finance, base)
        store.rebuild()
        val book = repo.readBook()
        assertEquals(eur("-100"), projectMonthBalance(config, book.costsFor("house"), book.activeRepayments("house"), java.time.YearMonth.from(tx.date)).closing)
        assertEquals(listOf(tx), book.transactions)
        assertTrue(runCatching { repo.changeCurrency(project.copy(currency = Currency.RSD), emptyList(), project) }.isFailure)
        repo.saveFinance(finance.copy(repayments = emptyList()), finance)
        assertTrue(repo.readBook().finance("house").repayments.isEmpty())
    }

    @Test fun `linked income must match and cannot be reused while deleted income is excluded`() = runTest {
        seed()
        val income = tx.copy(id = "income", type = TransactionType.INCOME, categoryId = null)
        store.append(TransactionAdded(income))
        val config = ProjectReimbursement("Dad", eur("0"), tx.date.withDayOfMonth(1))
        val repayment = ProjectRepayment("r", ProjectPayment(income.money, income.money, income.date, income.id))
        val finance = base.copy(reimbursement = config, repayments = listOf(repayment))
        assertTrue(runCatching { repo.saveFinance(finance.copy(repayments = listOf(repayment.copy(payment = repayment.payment.copy(original = eur("100"), converted = eur("100"))))), base) }.isFailure)
        repo.saveFinance(finance, base)
        assertTrue(income.id in repo.readBook().linkedTransactionIds())
        store.append(TransactionDeleted(income.id))
        assertTrue(repo.readBook().activeRepayments("house").isEmpty())
        assertTrue(repo.readBook().paymentNeedsReview(repayment.payment, TransactionType.INCOME))
    }

    @Test fun `income settles multiple unpaid bills with adjusted amounts and survives replay without changing income`() = runTest {
        seed()
        val income = tx.copy(id = "dad-income", type = TransactionType.INCOME, categoryId = null)
        store.append(TransactionAdded(income))
        val history = store.snapshot()
        store.activate("user", "family", "dataset", "member", 1, history, HistoryCodec.digest(HistoryCodec.encode(history)), true)
        val entry = settlement.copy(type = TransactionType.INCOME, payment = settlement.payment.copy(transactionId = income.id))
        val finance = base.copy(settlements = listOf(entry))
        repo.saveFinance(finance, base, listOf(a, b))
        val operation = OperationCodec.decode(db.syncOperationQueries.all().executeAsList().single().payload)
        assertEquals(ResourceAccess.WRITE, operation.resources["transaction:dad-income"])
        store.rebuild()
        store.validateHistory(store.snapshot())
        val book = repo.readBook()
        assertEquals(finance, book.finance("house"))
        assertEquals(listOf(a, b), book.costs)
        assertEquals(listOf(eur("550"), eur("350")), book.costsFor("house").map { it.converted })
        assertTrue(book.costsFor("house").none { it.unpaid })
        assertFalse(book.needsReview(a))
        assertEquals(income, book.transactions.single { it.id == income.id })
        assertEquals(2, book.transactions.size)
        repo.saveFinance(base, finance)
        assertEquals(listOf(a, b), repo.readBook().costsFor("house"))
        assertEquals(income, repo.readBook().transactions.single { it.id == income.id })
    }

    @Test fun `income settlement rejects wrong transaction type and reuse and detects edits or deletion`() = runTest {
        seed()
        val entry = settlement.copy(type = TransactionType.INCOME)
        assertTrue(runCatching { repo.saveFinance(base.copy(settlements = listOf(entry)), base, listOf(a, b)) }.isFailure)
        val income = tx.copy(id = "income", type = TransactionType.INCOME, categoryId = null)
        store.append(TransactionAdded(income))
        val finance = base.copy(settlements = listOf(entry.copy(payment = entry.payment.copy(transactionId = income.id))))
        repo.saveFinance(finance, base, listOf(a, b))
        val config = ProjectReimbursement("Dad", eur("0"), tx.date.withDayOfMonth(1))
        val duplicate = ProjectRepayment("duplicate", ProjectPayment(income.money, income.money, income.date, income.id))
        assertTrue(runCatching { repo.saveFinance(finance.copy(reimbursement = config, repayments = listOf(duplicate)), finance) }.isFailure)
        store.append(TransactionEdited(income.copy(money = eur("850"))))
        assertTrue(repo.readBook().needsReview(a))
        store.append(TransactionDeleted(income.id))
        assertTrue(repo.readBook().costsFor("house").all { it.unpaid })
        repo.saveFinance(base, finance)
    }

    @Test fun `finance operations announce dependencies survive sync replay and undo`() = runTest {
        seed(sync = true)
        repo.saveFinance(paid, base, listOf(a, b))
        val operation = OperationCodec.decode(db.syncOperationQueries.all().executeAsList().single().payload)
        assertEquals(6, operation.version)
        assertEquals(ResourceAccess.WRITE, operation.resources["project:house"])
        assertEquals(ResourceAccess.WRITE, operation.resources["transaction:expense"])
        store.rebuild()
        store.validateHistory(store.snapshot())
        assertEquals(paid, repo.readBook().finance("house"))
        repo.saveFinance(base, paid)
        store.rebuild()
        assertEquals(base, repo.readBook().finance("house"))
    }
}
