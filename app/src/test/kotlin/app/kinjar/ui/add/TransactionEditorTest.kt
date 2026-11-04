package app.kinjar.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.FixedClock
import app.kinjar.data.Projector
import app.kinjar.data.TransactionAccountStore
import app.kinjar.data.testDb
import app.kinjar.domain.Account
import app.kinjar.domain.Cadence
import app.kinjar.domain.Currency
import app.kinjar.domain.Loan
import app.kinjar.domain.Money
import app.kinjar.domain.Receipt
import app.kinjar.domain.RecurringRule
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import app.kinjar.domain.billTxId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionEditorTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val db = testDb()
    private val store = EventStore(db, "dev-1", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "dev-1")
    private val remembered = mutableMapOf<TransactionType, String>()
    private val accountStore = TransactionAccountStore(remembered::get) { type, id -> remembered[type] = id }
    private fun rsd(v: String) = Money.of(v, Currency.RSD)
    private fun tx(id: String, source: TransactionSource = TransactionSource.MANUAL, account: String = "cash") =
        Transaction(id, TransactionType.EXPENSE, rsd("100"), account, null, "cat-groceries", clock.today().minusDays(2), "kept", "Shop", "other-member", source)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    private val models = mutableListOf<ViewModel>()
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private suspend fun seed() = repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD), Account("card", "Card", Currency.RSD), Account("eur", "EUR", Currency.EUR)))

    private suspend fun editor(id: String, handle: SavedStateHandle = SavedStateHandle(mapOf("id" to id))): AddViewModel =
        AddViewModel(repo, clock, handle, accountStore).also { models += it }.also { vm -> vm.state.first { (it.editing != null || it.missing) && it.categories.isNotEmpty() } }

    @Test fun `correcting an entry keeps id payer source and note and leaves account preference alone`() = runTest(dispatcher) {
        seed()
        repo.add(tx("t"))
        val vm = editor("t")
        val s = vm.state.value
        assertNull(s.savedReceipt)
        assertEquals("100", s.amount.text)
        assertEquals("cash", s.accountId)
        assertEquals(clock.today().minusDays(2), s.date)
        "5".forEach(vm::press)
        vm.setAccount("card")
        vm.setDate(clock.today().plusDays(3))
        vm.setMerchant(" Market ")
        vm.save()
        vm.state.first { it.saved }
        val saved = repo.transactions.first().single()
        assertEquals(tx("t").copy(money = rsd("1005"), accountId = "card", date = clock.today().plusDays(3), merchant = "Market"), saved)
        assertTrue(remembered.isEmpty())
        assertEquals(rsd("0"), repo.home.first().balances["card"])
    }

    @Test fun `ordinary entries can change type and transfers need an active destination`() = runTest(dispatcher) {
        seed()
        repo.add(Transaction("x", TransactionType.TRANSFER, rsd("50"), "cash", "card", null, clock.today(), "", "", "m", TransactionSource.MANUAL))
        repo.archiveAccount("card")
        val vm = editor("x")
        assertEquals("card", vm.state.value.toAccountId)
        assertFalse(vm.state.value.canSave)
        vm.setType(TransactionType.INCOME)
        assertTrue(vm.state.value.canSave)
        vm.save()
        vm.state.first { it.saved }
        val saved = repo.transactions.first().single()
        assertEquals(TransactionType.INCOME, saved.type)
        assertNull(saved.toAccountId)
        assertNull(saved.categoryId)
    }

    @Test fun `archived account and category are never swapped silently`() = runTest(dispatcher) {
        seed()
        repo.add(tx("t"))
        repo.archiveAccount("cash")
        repo.archiveCategory("cat-groceries")
        val vm = editor("t")
        val s = vm.state.value
        assertNull(s.accountId)
        assertEquals("cat-groceries", s.categoryId)
        assertFalse(s.canSave)
        vm.setAccount("card")
        assertFalse(vm.state.value.canSave)
        vm.setCategory(vm.state.value.categories.first().id)
        assertTrue(vm.state.value.canSave)
        vm.save()
        vm.state.first { it.saved }
        assertEquals("card", repo.transactions.first().single().accountId)
    }

    @Test fun `receipt corrections stay RSD expenses`() = runTest(dispatcher) {
        seed()
        val receipt = Receipt("https://suf.purs.gov.rs/v/?vl=x", "1", "INV-1", "Shop", clock.today(), rsd("100"), emptyList())
        repo.addReceipt(tx(receipt.transactionId, TransactionSource.RECEIPT), receipt)
        val vm = editor(receipt.transactionId)
        assertEquals(receipt, vm.state.value.savedReceipt)
        assertNull(vm.state.value.receipt)
        vm.setType(TransactionType.INCOME)
        assertEquals(TransactionType.EXPENSE, vm.state.value.type)
        vm.setAccount("eur")
        assertFalse(vm.state.value.canSave)
        vm.setAccount("card")
        vm.press('5')
        vm.setMerchant("Corrected merchant")
        vm.save()
        vm.state.first { it.saved }
        assertEquals(receipt.transactionId, repo.transactions.first().single().id)
        assertEquals(TransactionSource.RECEIPT, repo.transactions.first().single().source)
        assertEquals(rsd("1005"), repo.transactions.first().single().money)
        assertEquals(receipt, repo.receiptForTransaction(receipt.transactionId))
        assertEquals(receipt, editor(receipt.transactionId).state.value.savedReceipt)
    }

    @Test fun `failed receipt load and retry cannot create a new transaction`() = runTest(dispatcher) {
        seed()
        val receipt = Receipt("https://suf.purs.gov.rs/v/?vl=x", "1", "INV-1", "Shop", clock.today(), rsd("100"), emptyList())
        val original = tx(receipt.transactionId, TransactionSource.RECEIPT)
        repo.addReceipt(original, receipt)
        db.receiptQueries.deleteAll()
        db.receiptQueries.insert(receipt.id, receipt.transactionId, receipt.issuer, receipt.invoiceNumber,
            receipt.fiscalUrl, receipt.merchant, receipt.date.toString(), "100.00", "broken")
        val handle = SavedStateHandle(mapOf("id" to receipt.transactionId))
        val vm = AddViewModel(repo, clock, handle, accountStore).also { models += it }
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.canSave)
        vm.state.first { it.loadFailed }
        vm.press('5')
        vm.setAccount("cash")
        vm.save()
        vm.delete()
        advanceUntilIdle()
        assertEquals(listOf(original), repo.transactions.first())
        assertFalse(vm.state.value.canSave)
        assertTrue(handle.keys().none { it.startsWith("draft.") })

        store.rebuild()
        vm.retryLoad()
        assertTrue(vm.state.value.loading)
        vm.save()
        vm.delete()
        vm.state.first { !it.loading && it.editing != null }
        assertEquals(receipt, vm.state.value.savedReceipt)
        assertEquals("100", vm.state.value.amount.text)
        val loaded = vm.state.value
        assertTrue(loaded.canSave)
        assertFalse(loaded.copy(loading = true).canSave)
        assertFalse(loaded.copy(loadFailed = true).canSave)
        vm.setMerchant("Corrected")
        vm.save()
        vm.state.first { it.saved }
        assertEquals(listOf(original.copy(merchant = "Corrected")), repo.transactions.first())
        assertEquals(receipt, repo.receiptForTransaction(receipt.transactionId))
        assertTrue(handle.keys().none { handle.get<Any>(it) == receipt.fiscalUrl })
    }

    @Test fun `paid bill cannot move into the future and deleting it reopens the bill`() = runTest(dispatcher) {
        seed()
        repo.setRule(RecurringRule("rent", "Rent", rsd("100"), "cash", "cat-bills", Cadence.MONTHLY, LocalDate.of(2026, 10, 1)))
        val id = billTxId("rent", "2026-10")
        repo.add(tx(id, TransactionSource.RECURRING).copy(categoryId = "cat-bills"))
        val vm = editor(id)
        vm.setDate(clock.today().plusDays(1))
        assertTrue(vm.state.value.billInFuture)
        assertFalse(vm.state.value.canSave)
        vm.setDate(clock.today())
        assertTrue(vm.state.value.canSave)
        assertTrue(repo.upcomingBills.first().none { it.txId == id })
        vm.delete()
        vm.state.first { it.saved }
        assertTrue(repo.transactions.first().isEmpty())
        assertTrue(repo.upcomingBills.first().any { it.txId == id })
    }

    @Test fun `loan transactions and missing ids open read only`() = runTest(dispatcher) {
        seed()
        val credit = Transaction("credit", TransactionType.LOAN_RECEIPT, rsd("1000"), "cash", null, null, clock.today(), "", "", "m", TransactionSource.MANUAL)
        repo.loans.create(Loan("l", "Car", rsd("1000"), clock.today()), credit)
        val locked = editor("credit")
        assertTrue(locked.state.value.loanLocked)
        assertFalse(locked.state.value.canSave)
        locked.save()
        locked.delete()
        advanceUntilIdle()
        assertEquals(listOf(credit), repo.transactions.first())

        val missing = editor("gone")
        assertTrue(missing.state.value.missing)
        assertFalse(missing.state.value.canSave)
        "5".forEach(missing::press)
        missing.save()
        advanceUntilIdle()
        assertEquals(1, repo.transactions.first().size)
    }

    @Test fun `ordinary draft survives process recreation within the same rules`() = runTest(dispatcher) {
        seed()
        val handle = SavedStateHandle()
        val first = AddViewModel(repo, clock, handle, accountStore).also { models += it }.also { vm -> vm.state.first { it.accounts.isNotEmpty() } }
        first.setType(TransactionType.TRANSFER)
        first.setAccount("cash")
        first.setToAccount("card")
        "12.5".forEach(first::press)
        first.setDate(clock.today().plusDays(1))
        first.setMerchant("Rent split")
        advanceUntilIdle()

        val restored = AddViewModel(repo, clock, handle, accountStore).also { models += it }.also { vm -> vm.state.first { it.accounts.isNotEmpty() } }
        val s = restored.state.value
        assertEquals(TransactionType.TRANSFER, s.type)
        assertEquals("12.5", s.amount.text)
        assertEquals("cash", s.accountId)
        assertEquals("card", s.toAccountId)
        assertEquals(clock.today().plusDays(1), s.date)
        assertEquals("Rent split", s.merchant)
        assertTrue(s.canSave)

        val tampered = SavedStateHandle(mapOf("draft.type" to "LOAN_RECEIPT", "draft.amount" to "1x2.345", "draft.date" to "nope",
            "draft.category" to "cat-fun", "draft.account" to "eur", "draft.merchant" to "M"))
        val sanitized = AddViewModel(repo, clock, tampered, accountStore).also { models += it }.also { vm -> vm.state.first { it.accounts.isNotEmpty() } }.state.value
        assertEquals(TransactionType.EXPENSE, sanitized.type)
        assertEquals("12.34", sanitized.amount.text)
        assertEquals(clock.today(), sanitized.date)
        assertEquals(Currency.EUR, sanitized.currency)
    }

    @Test fun `bill and edit drafts restore over their source while receipts are never persisted`() = runTest(dispatcher) {
        seed()
        repo.setRule(RecurringRule("rent", "Rent", rsd("100"), "cash", "cat-bills", Cadence.MONTHLY, LocalDate.of(2026, 10, 20)))
        val billHandle = SavedStateHandle(mapOf("rule" to "rent", "due" to "2026-10-20"))
        val bill = AddViewModel(repo, clock, billHandle, accountStore).also { models += it }.also { vm -> vm.state.first { it.bill != null && it.accounts.isNotEmpty() } }
        bill.press('5')
        bill.setAccount("card")
        advanceUntilIdle()
        billHandle["draft.type"] = TransactionType.INCOME.name
        billHandle["draft.date"] = "2030-01-01"
        val billRestored = AddViewModel(repo, clock, billHandle, accountStore).also { models += it }.also { vm -> vm.state.first { it.bill != null && it.accounts.isNotEmpty() } }.state.value
        assertEquals("1005", billRestored.amount.text)
        assertEquals("card", billRestored.accountId)
        assertEquals(TransactionType.EXPENSE, billRestored.type)
        assertEquals(clock.today(), billRestored.date)

        repo.add(tx("t"))
        val editHandle = SavedStateHandle(mapOf("id" to "t"))
        editor("t", editHandle).setMerchant("Changed")
        advanceUntilIdle()
        assertEquals("Changed", editor("t", editHandle).state.value.merchant)

        val scanHandle = SavedStateHandle()
        val scan = AddViewModel(repo, clock, scanHandle, accountStore).also { models += it }.also { vm -> vm.state.first { it.accounts.isNotEmpty() } }
        scan.prefillReceipt(Receipt("https://suf.purs.gov.rs/v/?vl=secret", "1", "INV-2", "Shop", clock.today(), rsd("10"), emptyList()), null)
        advanceUntilIdle()
        assertTrue(scanHandle.keys().none { it.startsWith("draft.") })
    }
}
