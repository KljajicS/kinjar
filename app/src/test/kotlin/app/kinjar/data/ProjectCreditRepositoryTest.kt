package app.kinjar.data

import app.kinjar.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProjectCreditRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-15T12:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "local", clock, Projector(db))
    private val repo = ProjectRepository(db, store, clock)
    private val project = Project("radovan", "Radovan", Currency.RSD)
    private fun rsd(value: String) = Money.of(value, Currency.RSD)
    private fun bill(id: String, amount: String) = ProjectCost(id, project.id, null, rsd(amount), rsd(amount), clock.today(), id, unpaid = true)
    private val empty = ProjectFinance(project.id)
    private val opening = empty.copy(openingCredit = rsd("7000"))

    private suspend fun seed() {
        store.appendAll(listOf(AccountCreated(Account("bank", "Bank", Currency.RSD)), ProjectSaved(project)))
        repo.saveFinance(opening, empty)
    }

    @Test fun `explicit full credit payment keeps bill total and account transactions unchanged through replay`() = runTest {
        seed()
        val cost = bill("internet", "3000")
        repo.recordFromCredit(cost, null, opening)
        store.rebuild()
        store.validateHistory(store.snapshot())
        val book = repo.readBook()
        assertEquals(listOf(cost), book.costs)
        assertEquals(cost.copy(unpaid = false), book.costsFor(project.id).single())
        assertEquals(rsd("4000"), book.incomeBalance(project.id))
        assertEquals(rsd("0"), book.unpaidTotal(project.id))
        assertTrue(book.transactions.isEmpty())
        repo.record(listOf(bill("leave-unpaid", "1000")))
        assertEquals(rsd("1000"), repo.readBook().unpaidTotal(project.id))
        assertEquals(rsd("4000"), repo.readBook().incomeBalance(project.id))
    }

    @Test fun `partial credit leaves remainder and later income settles only remainder without double counting`() = runTest {
        seed()
        val cost = bill("electricity", "9000")
        repo.recordFromCredit(cost, null, opening)
        var book = repo.readBook()
        assertEquals(cost, book.costsFor(project.id).single())
        assertEquals(rsd("2000"), book.unpaidAmount(book.costsFor(project.id).single()))
        assertEquals(rsd("2000"), book.unpaidTotal(project.id))
        assertEquals(rsd("0"), book.incomeBalance(project.id))
        val credited = book.finance(project.id)
        val income = Transaction("income", TransactionType.INCOME, rsd("2500"), "bank", null, null, clock.today(), "", "", "member", TransactionSource.MANUAL)
        store.append(TransactionAdded(income))
        val settlement = ProjectSettlement("rest", ProjectPayment(income.money, income.money, income.date, income.id), mapOf(cost.id to rsd("2000")), TransactionType.INCOME)
        val linked = credited.copy(settlements = listOf(settlement))
        repo.saveFinance(linked, credited, listOf(cost))
        store.rebuild()
        store.validateHistory(store.snapshot())
        book = repo.readBook()
        assertEquals(cost.copy(unpaid = false), book.costsFor(project.id).single())
        assertEquals(rsd("500"), book.incomeBalance(project.id))
        assertEquals(rsd("0"), book.unpaidTotal(project.id))
        assertTrue(runCatching { repo.saveFinance(linked.copy(creditCosts = emptyMap()), linked) }.isFailure)
        repo.saveFinance(credited, linked)
        repo.saveFinance(opening, credited)
        book = repo.readBook()
        assertEquals(cost, book.costsFor(project.id).single())
        assertEquals(rsd("9000"), book.unpaidTotal(project.id))
        assertEquals(rsd("7000"), book.incomeBalance(project.id))
        assertEquals(listOf(income), book.transactions)
    }

    @Test fun `stale credit no credit edits and removal fail atomically`() = runTest {
        seed()
        val cost = bill("one", "7000")
        repo.recordFromCredit(cost, null, opening)
        val paid = repo.readBook().finance(project.id)
        assertTrue(runCatching { repo.recordFromCredit(bill("stale", "1000"), null, opening) }.isFailure)
        assertTrue(runCatching { repo.recordFromCredit(bill("empty", "1000"), null, paid) }.isFailure)
        assertTrue(runCatching { repo.record(listOf(cost.copy(note = "changed")), listOf(cost)) }.isFailure)
        assertTrue(runCatching { repo.removeCost(cost.id, cost) }.isFailure)
        assertTrue(runCatching { repo.saveFinance(paid.copy(openingCredit = rsd("6000")), paid) }.isFailure)
        assertTrue(runCatching { repo.changeCurrency(project.copy(currency = Currency.EUR), emptyList(), project) }.isFailure)
        assertEquals(listOf(cost), repo.readBook().costs)
        assertEquals(paid, repo.readBook().finance(project.id))
        repo.saveFinance(opening, paid)
        assertEquals(rsd("7000"), repo.readBook().incomeBalance(project.id))
        assertTrue(repo.readBook().costsFor(project.id).single().unpaid)
    }

    @Test fun `existing unpaid bill can explicitly use credit with stale cost protection`() = runTest {
        seed()
        val cost = bill("existing", "9000")
        repo.record(listOf(cost))
        assertTrue(runCatching { repo.recordFromCredit(cost, cost.copy(note = "stale"), opening) }.isFailure)
        assertEquals(opening, repo.readBook().finance(project.id))
        repo.recordFromCredit(cost, cost, opening)
        assertEquals(rsd("2000"), repo.readBook().unpaidTotal(project.id))
    }

    @Test fun `income shortfall may become debit after credit was used`() = runTest {
        seed()
        val cost = bill("partial", "9000")
        repo.recordFromCredit(cost, null, opening)
        val credited = repo.readBook().finance(project.id)
        val income = Transaction("short", TransactionType.INCOME, rsd("1500"), "bank", null, null, clock.today(), "", "", "member", TransactionSource.MANUAL)
        store.append(TransactionAdded(income))
        val settlement = ProjectSettlement("short", ProjectPayment(income.money, income.money, income.date, income.id), mapOf(cost.id to rsd("2000")), TransactionType.INCOME)
        repo.saveFinance(credited.copy(settlements = listOf(settlement)), credited, listOf(cost))
        assertEquals(rsd("-500"), repo.readBook().incomeBalance(project.id))
        assertEquals(rsd("0"), repo.readBook().unpaidTotal(project.id))
        assertFalse(repo.readBook().needsReview(cost))
    }

    @Test fun `overflow credit can be spent but its funding cannot be undone until credit payment is undone`() = runTest {
        seed()
        repo.saveFinance(empty, opening)
        val first = bill("first", "8000")
        repo.record(listOf(first))
        val income = Transaction("excess", TransactionType.INCOME, rsd("18000"), "bank", null, null, clock.today(), "", "", "member", TransactionSource.MANUAL)
        store.append(TransactionAdded(income))
        val settlement = ProjectSettlement("excess", ProjectPayment(income.money, income.money, income.date, income.id), mapOf(first.id to first.converted), TransactionType.INCOME)
        val funded = empty.copy(settlements = listOf(settlement))
        repo.saveFinance(funded, empty, listOf(first))
        repo.recordFromCredit(bill("second", "3000"), null, funded)
        val spent = repo.readBook().finance(project.id)
        assertEquals(rsd("7000"), repo.readBook().incomeBalance(project.id))
        assertTrue(runCatching { repo.saveFinance(spent.copy(settlements = emptyList()), spent) }.isFailure)
        store.append(TransactionEdited(income.copy(money = rsd("10000"))))
        assertTrue(runCatching { repo.recordFromCredit(bill("stale-income", "1000"), null, spent) }.isFailure)
        repo.saveFinance(funded, spent)
        repo.saveFinance(empty, funded)
        assertEquals(rsd("11000"), repo.readBook().unpaidTotal(project.id))
    }

    @Test fun `credit operation version and project lock survive sync replay and defaults preserve legacy encoding`() = runTest {
        seed()
        val history = store.snapshot()
        store.activate("user", "family", "dataset", "member", 1, history, HistoryCodec.digest(HistoryCodec.encode(history)), true)
        repo.recordFromCredit(bill("credit", "3000"), null, opening)
        val op = OperationCodec.decode(db.syncOperationQueries.all().executeAsList().single().payload)
        assertEquals(8, op.version)
        assertEquals(2, op.events.size)
        assertEquals(ResourceAccess.WRITE, op.resources["project:radovan"])
        assertEquals(op, OperationCodec.decode(OperationCodec.encode(op)))
        store.rebuild()
        store.validateHistory(store.snapshot())
        assertEquals(rsd("4000"), repo.readBook().incomeBalance(project.id))
        val old = EventJson.encodeToString(ProjectFinance.serializer(), empty)
        assertFalse(old.contains("openingCredit"))
        assertFalse(old.contains("creditCosts"))
        assertEquals(empty, EventJson.decodeFromString(ProjectFinance.serializer(), old))
        val legacy = SyncOperation(familyId = op.familyId, datasetId = op.datasetId, authorUid = op.authorUid,
            installId = op.installId, sequence = op.sequence, parents = op.parents, resources = op.resources,
            events = listOf(op.events.last().copy(event = ProjectFinanceSaved(empty))))
        assertEquals(6, legacy.version)
        assertFalse(String(OperationCodec.encode(legacy)).contains("creditCosts"))
    }

    private suspend fun scanned(id: String, amount: String): Pair<Transaction, ProjectCost> {
        val tx = Transaction(id, TransactionType.EXPENSE, rsd(amount), "bank", null, "bills", clock.today(), "Scanned bill", "", "member", TransactionSource.RECEIPT)
        store.append(TransactionAdded(tx))
        return tx to bill("cost-$id", amount).copy(transactionId = id, reimbursable = true)
    }

    private suspend fun enableBills() {
        store.append(CategoryCreated(Category("bills", "Bills", "#123456")))
        repo.save(project.copy(sourceCategoryIds = setOf("bills")), project)
    }

    @Test fun `scanned expense uses partial credit then income without duplicating expense or changing original total`() = runTest {
        seed()
        enableBills()
        val (expense, cost) = scanned("receipt", "9000")
        val history = store.snapshot()
        store.activate("user", "family", "dataset", "member", 1, history, HistoryCodec.digest(HistoryCodec.encode(history)), true)
        repo.linkFromCredit(listOf(cost), opening)
        val op = OperationCodec.decode(db.syncOperationQueries.all().executeAsList().single().payload)
        assertEquals(8, op.version)
        assertEquals(ResourceAccess.WRITE, op.resources["transaction:receipt"])
        var book = repo.readBook()
        assertEquals(listOf(expense), book.transactions)
        assertEquals(cost, book.costsFor(project.id).single())
        assertEquals(rsd("2000"), book.unpaidTotal(project.id))
        assertEquals(rsd("-2000"), book.incomeBalance(project.id))
        assertTrue(expense.id in book.linkedTransactionIds())
        val credited = book.finance(project.id)
        val income = expense.copy(id = "repayment", type = TransactionType.INCOME, money = rsd("2500"), categoryId = null, source = TransactionSource.MANUAL)
        store.append(TransactionAdded(income))
        val settlement = ProjectSettlement("rest", ProjectPayment(income.money, income.money, income.date, income.id), mapOf(cost.id to rsd("2000")), TransactionType.INCOME)
        repo.saveFinance(credited.copy(settlements = listOf(settlement)), credited, listOf(cost))
        store.rebuild()
        store.validateHistory(store.snapshot())
        book = repo.readBook()
        assertEquals(cost.copy(unpaid = false), book.costsFor(project.id).single())
        assertEquals(rsd("500"), book.incomeBalance(project.id))
        assertEquals(listOf(expense, income).toSet(), book.transactions.toSet())
        store.append(TransactionEdited(expense.copy(money = rsd("9500"))))
        assertTrue(repo.readBook().needsReview(cost))
    }

    @Test fun `multiple scanned bills allocate credit in list order and undo leaves reimbursement due`() = runTest {
        seed()
        enableBills()
        val (a, first) = scanned("a", "3000")
        val (b, second) = scanned("b", "6000")
        val (c, third) = scanned("c", "1000")
        repo.linkFromCredit(listOf(first, second, third), opening)
        val book = repo.readBook()
        assertEquals(mapOf(first.id to rsd("3000"), second.id to rsd("4000")), book.finance(project.id).creditCosts)
        assertEquals(rsd("3000"), book.unpaidTotal(project.id))
        assertFalse(book.costsFor(project.id).single { it.id == first.id }.unpaid)
        assertTrue(book.costsFor(project.id).single { it.id == third.id }.unpaid)
        assertEquals(setOf(a, b, c), book.transactions.toSet())
        assertTrue(runCatching { repo.linkFromCredit(listOf(third.copy(id = "duplicate")), opening) }.isFailure)
        repo.saveFinance(opening, book.finance(project.id))
        assertEquals(rsd("10000"), repo.readBook().unpaidTotal(project.id))
        assertEquals(rsd("-3000"), repo.readBook().incomeBalance(project.id))
        repo.removeCost(first.id, first)
        assertEquals(setOf(a, b, c), repo.readBook().transactions.toSet())
    }

    @Test fun `changed scanned expense rejects entire credit link and legacy cost encoding remains unchanged`() = runTest {
        seed()
        enableBills()
        val (tx, cost) = scanned("stale", "9000")
        store.append(TransactionEdited(tx.copy(money = rsd("9500"))))
        assertTrue(runCatching { repo.linkFromCredit(listOf(cost), opening) }.isFailure)
        assertEquals(opening, repo.readBook().finance(project.id))
        assertTrue(repo.readBook().costs.isEmpty())
        val legacy = bill("old", "1000")
        assertFalse(EventJson.encodeToString(ProjectCost.serializer(), legacy).contains("reimbursable"))
    }

    @Test fun `zero credit and existing debt allow tracking and income clears debt exactly once`() = runTest {
        seed()
        enableBills()
        repo.saveFinance(empty, opening)
        val (expense, cost) = scanned("zero-credit", "9000")
        repo.linkFromCredit(listOf(cost), empty)
        assertEquals(rsd("-9000"), repo.readBook().incomeBalance(project.id))
        assertEquals(rsd("9000"), repo.readBook().unpaidTotal(project.id))
        val (_, another) = scanned("already-debit", "1000")
        repo.linkFromCredit(listOf(another), empty)
        assertEquals(rsd("-10000"), repo.readBook().incomeBalance(project.id))
        val income = expense.copy(id = "received", type = TransactionType.INCOME, money = rsd("9000"), categoryId = null, source = TransactionSource.MANUAL)
        store.append(TransactionAdded(income))
        val settlement = ProjectSettlement("received", ProjectPayment(income.money, income.money, income.date, income.id), mapOf(cost.id to rsd("9000")), TransactionType.INCOME)
        repo.saveFinance(empty.copy(settlements = listOf(settlement)), empty, listOf(cost))
        store.rebuild()
        store.validateHistory(store.snapshot())
        assertEquals(rsd("-1000"), repo.readBook().incomeBalance(project.id))
        assertEquals(rsd("1000"), repo.readBook().unpaidTotal(project.id))
        repo.removeCost(another.id, another)
        assertEquals(rsd("0"), repo.readBook().incomeBalance(project.id))
        assertFalse(repo.readBook().costsFor(project.id).single().unpaid)
        assertEquals(3, repo.readBook().transactions.size)
    }
}
