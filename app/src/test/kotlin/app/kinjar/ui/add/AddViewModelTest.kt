package app.kinjar.ui.add

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import app.kinjar.R
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.FixedClock
import app.kinjar.data.Projector
import app.kinjar.data.TransactionAccountStore
import app.kinjar.data.receipt.NbsIpsParser
import app.kinjar.data.db.KinjarDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.lifecycle.SavedStateHandle
import app.kinjar.domain.Account
import app.kinjar.domain.AccountKind
import app.kinjar.domain.Cadence
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.RecurringRule
import app.kinjar.domain.Receipt
import app.kinjar.domain.ReceiptItem
import java.math.BigDecimal
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import app.kinjar.domain.billTxId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AddViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = KinjarDb(driver).also { KinjarDb.Schema.create(driver) }
    private val repo = BudgetRepository(db, EventStore(db, "dev-1", clock, Projector(db)), clock, "dev-1")
    private val remembered = mutableMapOf<TransactionType, String>()
    private val accountStore = TransactionAccountStore(remembered::get) { type, id -> remembered[type] = id }
    private val models = mutableListOf<AddViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        try {
            runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        } finally {
            Dispatchers.resetMain()
            driver.close()
        }
    }

    private suspend fun seeded(store: TransactionAccountStore = accountStore): AddViewModel {
        repo.setUp("S", listOf(
            Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING),
            Account("card", "Card", Currency.RSD, AccountKind.SPENDING),
            Account("eur", "EUR", Currency.EUR, AccountKind.SAVINGS),
        ))
        // Repository flows query on Dispatchers.IO, so wait for real data instead of advancing the virtual clock.
        return newVm(store)
    }

    private suspend fun newVm(store: TransactionAccountStore = accountStore): AddViewModel =
        AddViewModel(repo, clock, SavedStateHandle(), store).also { models += it }
            .also { vm -> vm.state.first { it.accounts.isNotEmpty() } }

    @Test
    fun `save disabled until amount entered, then saves expense with defaults`() = runTest(dispatcher) {
        val vm = seeded()
        assertFalse(vm.state.value.canSave)
        vm.setAmount("1240")
        vm.setCategory("cat-household")
        assertTrue(vm.state.value.canSave)
        vm.save()
        vm.state.first { it.saved }
        val t = repo.transactions.first().single()
        assertEquals(TransactionType.EXPENSE, t.type)
        assertEquals("1240.00", t.money.amount.toPlainString())
        assertEquals(vm.state.value.accounts.first().id, t.accountId)
        assertEquals(clock.today(), t.date)
    }

    @Test
    fun `manual entry replaces archived default category before saving`() = runTest(dispatcher) {
        val vm = seeded()
        assertEquals("cat-groceries", vm.state.value.categoryId)
        repo.archiveCategory("cat-groceries")
        val state = vm.state.first { it.categories.none { category -> category.id == "cat-groceries" } }
        assertTrue(state.categories.any { it.id == state.categoryId })
        assertEquals(state.categories.first().id, state.categoryId)
        vm.setAmount("100")
        vm.save()
        vm.state.first { it.saved }
        assertEquals(state.categoryId, repo.transactions.first().single().categoryId)
    }

    @Test
    fun `zero amount cannot be saved`() = runTest(dispatcher) {
        val vm = seeded()
        vm.setAmount("0.00")
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `transfer to same account is rejected with a message`() = runTest(dispatcher) {
        val vm = seeded()
        vm.setType(TransactionType.TRANSFER)
        vm.setAmount("500")
        vm.setAccount("cash")
        vm.setToAccount("cash")
        assertFalse(vm.state.value.canSave)
        assertEquals(R.string.add_same_account, vm.state.value.error)
    }

    @Test
    fun `transfer across currencies without a rate waits for the received amount`() = runTest(dispatcher) {
        val vm = seeded()
        vm.setType(TransactionType.TRANSFER)
        vm.setAmount("500")
        vm.setAccount("cash")
        vm.setToAccount("eur")
        assertNull(vm.state.value.error)
        assertEquals(ReceivedSource.MISSING, vm.state.value.receivedSource)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `choosing an account sets the currency`() = runTest(dispatcher) {
        val vm = seeded()
        vm.setAccount("eur")
        assertEquals(Currency.EUR, vm.state.value.currency)
        assertNotNull(vm.state.value.accounts.firstOrNull { it.id == "eur" })
    }

    private suspend fun billVm(): AddViewModel {
        seeded()
        repo.setRule(RecurringRule("rent", "Rent", Money.of("40000", Currency.RSD), "cash", "cat-bills", Cadence.MONTHLY, LocalDate.of(2026, 10, 20)))
        val vm = AddViewModel(repo, clock, SavedStateHandle(mapOf("rule" to "rent", "due" to "2026-10-20")), accountStore).also { models += it }
        vm.state.first { it.bill != null && it.accounts.isNotEmpty() }
        return vm
    }

    @Test
    fun `a bill pre-fills the sheet, locks the type and saves with the derived id`() = runTest(dispatcher) {
        val vm = billVm()
        val s = vm.state.value
        assertEquals("40000", s.amount.text)
        assertEquals("cash", s.accountId)
        assertEquals("cat-bills", s.categoryId)
        assertEquals("Rent", s.merchant)
        assertEquals(clock.today(), s.date)
        vm.setDate(clock.today().plusDays(5))
        assertEquals(clock.today(), vm.state.value.date)
        vm.setType(TransactionType.INCOME)
        assertEquals(TransactionType.EXPENSE, vm.state.value.type)
        vm.setAmount(vm.state.value.amount.text.dropLast(1))
        vm.setAmount(vm.state.value.amount.text + "5")
        vm.save()
        vm.state.first { it.saved }
        val t = repo.transactions.first().single()
        assertEquals(billTxId("rent", "2026-10"), t.id)
        assertEquals(TransactionSource.RECURRING, t.source)
        assertEquals("40005.00", t.money.amount.toPlainString())
        assertEquals(0, repo.upcomingBills.first().size)
    }

    @Test
    fun `bill paid while form is open rejects changed amount and reloads payment for correction`() = runTest(dispatcher) {
        val vm = billVm()
        val other = AddViewModel(repo, clock, SavedStateHandle(mapOf("rule" to "rent", "due" to "2026-10-20")), accountStore)
            .also { models += it }
        other.state.first { it.bill != null && it.accounts.isNotEmpty() }
        other.save()
        other.state.first { it.saved }
        val payment = repo.transactions.first().single()

        vm.setAmount(vm.state.value.amount.text.dropLast(1))
        vm.setAmount(vm.state.value.amount.text + "5")
        vm.save()
        val rejected = vm.state.first { it.saved || it.saveError != null }
        assertFalse(rejected.saved)
        assertTrue(rejected.stale)
        assertFalse(rejected.canSave)
        assertEquals(R.string.audit_edit_stale, rejected.saveError)
        assertEquals(payment, repo.transactions.first().single())

        vm.reload()
        vm.state.first { it.editing != null && !it.loading }
        assertFalse(vm.state.value.stale)
        assertNull(vm.state.value.bill)
        assertEquals(payment, vm.state.value.editing)
        assertEquals("40000", vm.state.value.amount.text)
        vm.setAmount(vm.state.value.amount.text.dropLast(1))
        vm.setAmount(vm.state.value.amount.text + "5")
        vm.save()
        vm.state.first { it.saved }
        assertEquals(payment.copy(money = Money.of("40005", Currency.RSD)), repo.transactions.first().single())
    }

    @Test
    fun `archiving bill account requires explicit replacement without changing currency`() = runTest(dispatcher) {
        val vm = billVm()
        repo.archiveAccount("cash")
        vm.state.first { s -> s.accounts.none { it.id == "cash" } }
        assertNull(vm.state.value.accountId)
        assertEquals(Currency.RSD, vm.state.value.currency)
        assertEquals("40000", vm.state.value.amount.text)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(repo.transactions.first().isEmpty())
        vm.setAccount("eur")
        assertEquals("eur", vm.state.value.accountId)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `skipping a bill writes a skip and closes the sheet`() = runTest(dispatcher) {
        val vm = billVm()
        vm.skip()
        vm.state.first { it.saved }
        assertEquals(0, repo.transactions.first().size)
        assertEquals(0, repo.upcomingBills.first().size)
    }

    private fun receipt() = Receipt("https://suf.purs.gov.rs/v/?vl=test", "123456789", "ABCDEFGH-IJKLMNOP-1", "Shop",
        LocalDate.of(2026, 10, 1), Money.of("123", Currency.RSD),
        listOf(ReceiptItem("Food", BigDecimal.ONE, BigDecimal("123"), BigDecimal("123"))))

    @Test fun `receipt prefill locks expense and RSD and preserves edits on repeated delivery`() = runTest(dispatcher) {
        val vm = seeded()
        val receipt = receipt()
        vm.prefillReceipt(receipt, "cat-household")
        vm.setType(TransactionType.INCOME)
        assertEquals(TransactionType.EXPENSE, vm.state.value.type)
        vm.setAccount("eur")
        assertFalse(vm.state.value.canSave)
        vm.setAccount("cash")
        vm.setMerchant("Edited")
        vm.setAmount(vm.state.value.amount.text.dropLast(1))
        vm.setAmount(vm.state.value.amount.text + "5")
        vm.prefillReceipt(receipt, "cat-groceries")
        assertEquals("Edited", vm.state.value.merchant)
        assertEquals("cat-household", vm.state.value.categoryId)
        vm.save()
        vm.save()
        vm.state.first { it.saved }
        val t = repo.transactions.first().single()
        assertEquals(receipt.transactionId, t.id)
        assertEquals(TransactionSource.RECEIPT, t.source)
        assertEquals("123.05", t.money.amount.toPlainString())
        assertEquals("123.00", db.receiptQueries.byId(receipt.id).executeAsOne().total)
    }

    @Test fun `unresolved receipt merchant requires manual entry and preserves that entry`() = runTest(dispatcher) {
        val vm = seeded()
        val unresolved = receipt().copy(merchant = "")
        vm.prefillReceipt(unresolved, null)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(repo.transactions.first().isEmpty())
        vm.setMerchant("My store")
        vm.prefillReceipt(unresolved, null)
        assertTrue(vm.state.value.canSave)
        vm.save()
        vm.state.first { it.saved }
        assertEquals("My store", repo.transactions.first().single().merchant)
        assertEquals("", repo.receiptForTransaction(unresolved.transactionId)?.merchant)
    }

    @Test fun `duplicate save shows error and retains receipt review`() = runTest(dispatcher) {
        val vm = seeded()
        vm.prefillReceipt(receipt(), null)
        vm.save()
        vm.state.first { it.saved }
        val previous = remembered.toMap()
        val second = newVm()
        second.prefillReceipt(receipt(), null)
        second.setAccount(if (vm.state.value.accountId == "cash") "card" else "cash")
        second.save()
        second.state.first { it.saveError != null }
        assertEquals(R.string.scan_duplicate, second.state.value.saveError)
        assertEquals(receipt(), second.state.value.receipt)
        assertEquals(1, repo.transactions.first().size)
        assertEquals(previous, remembered)
    }

    @Test fun `archived receipt account falls back and cancel clears draft`() = runTest(dispatcher) {
        val vm = seeded()
        vm.prefillReceipt(receipt(), null)
        val selected = vm.state.value.accountId!!
        repo.archiveAccount(selected)
        vm.state.first { it.accounts.none { a -> a.id == selected } }
        assertEquals(vm.state.value.accounts.first { it.currency == Currency.RSD }.id, vm.state.value.accountId)
        assertTrue(vm.state.value.canSave)
        vm.resetReceipt()
        assertEquals(null, vm.state.value.receipt)
        assertFalse(vm.state.value.canSave)
    }

    @Test fun `income and expense recall separate remembered accounts and currencies`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "cash"
        remembered[TransactionType.INCOME] = "eur"
        val vm = seeded()
        assertEquals("cash", vm.state.value.accountId)
        assertEquals(Currency.RSD, vm.state.value.currency)
        vm.setType(TransactionType.INCOME)
        assertEquals("eur", vm.state.value.accountId)
        assertEquals(Currency.EUR, vm.state.value.currency)
        vm.setType(TransactionType.EXPENSE)
        assertEquals("cash", vm.state.value.accountId)
        assertEquals(Currency.RSD, vm.state.value.currency)
    }

    @Test fun `in-form choices survive type switches and unrelated account or category emissions`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "cash"
        remembered[TransactionType.INCOME] = "eur"
        val vm = seeded()
        vm.setAccount("card")
        vm.setType(TransactionType.INCOME)
        vm.setAccount("cash")
        vm.setType(TransactionType.EXPENSE)
        assertEquals("card", vm.state.value.accountId)
        remembered[TransactionType.EXPENSE] = "eur"
        repo.addCategory("Extra", "#000000")
        vm.state.first { it.categories.any { category -> category.name == "Extra" } }
        assertEquals("card", vm.state.value.accountId)
        repo.updateAccount(vm.state.value.accounts.first { it.id == "cash" }.copy(name = "First account"))
        vm.state.first { it.accounts.any { account -> account.name == "First account" } }
        assertEquals("card", vm.state.value.accountId)
        vm.setType(TransactionType.INCOME)
        assertEquals("cash", vm.state.value.accountId)
    }

    @Test fun `only successful saves remember the account independently for each type across form restarts`() = runTest(dispatcher) {
        val expense = seeded()
        expense.setAccount("cash")
        expense.setAmount("100")
        assertTrue(remembered.isEmpty())
        expense.save()
        expense.state.first { it.saved }
        assertEquals(mapOf(TransactionType.EXPENSE to "cash"), remembered)

        val income = newVm()
        income.setType(TransactionType.INCOME)
        income.setAccount("eur")
        income.setAmount("200")
        income.save()
        income.state.first { it.saved }
        assertEquals(mapOf(TransactionType.EXPENSE to "cash", TransactionType.INCOME to "eur"), remembered)

        val reopened = newVm()
        assertEquals("cash", reopened.state.value.accountId)
        reopened.setType(TransactionType.INCOME)
        assertEquals("eur", reopened.state.value.accountId)
        assertEquals(2, repo.transactions.first().size)
    }

    @Test fun `save failure leaves remembered accounts unchanged and retry persists once`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "card"
        val vm = seeded()
        vm.setAccount("cash")
        vm.setAmount("100")
        driver.execute(null, "CREATE TRIGGER fail_tx BEFORE INSERT ON tx BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        vm.save()
        vm.state.first { it.saveError != null }
        assertFalse(vm.state.value.saved)
        assertEquals("card", remembered[TransactionType.EXPENSE])
        assertTrue(repo.transactions.first().isEmpty())

        driver.execute(null, "DROP TRIGGER fail_tx", 0)
        vm.save()
        vm.state.first { it.saved }
        assertEquals("cash", remembered[TransactionType.EXPENSE])
        assertEquals(1, repo.transactions.first().size)
    }

    @Test fun `preference write failure keeps persisted transaction successful and prevents retry duplicates`() = runTest(dispatcher) {
        val vm = seeded(TransactionAccountStore({ "card" }, { _, _ -> error("Preferences unavailable") }))
        vm.setAccount("cash")
        vm.setAmount("100")
        vm.save()
        vm.state.first { it.saved }
        assertEquals(null, vm.state.value.saveError)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertEquals(1, repo.transactions.first().size)
        assertEquals("cash", repo.transactions.first().single().accountId)
    }

    @Test fun `preference captures saved snapshot when user changes form during persistence`() = runTest(dispatcher) {
        val vm = seeded()
        vm.setAccount("cash")
        vm.setAmount("100")
        vm.save()
        vm.setAccount("eur")
        vm.setType(TransactionType.INCOME)
        vm.state.first { it.saved }
        assertEquals(mapOf(TransactionType.EXPENSE to "cash"), remembered)
        val saved = repo.transactions.first().single()
        assertEquals("cash", saved.accountId)
        assertEquals(TransactionType.EXPENSE, saved.type)
    }

    @Test fun `unavailable remembered accounts fall back in repository order and no accounts disables save`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "missing"
        remembered[TransactionType.INCOME] = "eur"
        val vm = seeded()
        assertEquals(repo.accounts.first().first().id, vm.state.value.accountId)
        repo.archiveAccount("eur")
        vm.state.first { it.accounts.none { account -> account.id == "eur" } }
        vm.setType(TransactionType.INCOME)
        assertEquals(repo.accounts.first().first().id, vm.state.value.accountId)
        vm.setAmount("100")
        for (account in repo.accounts.first()) repo.archiveAccount(account.id)
        vm.state.first { it.accounts.isEmpty() }
        assertEquals(null, vm.state.value.accountId)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(repo.transactions.first().isEmpty())
        assertEquals("missing", remembered[TransactionType.EXPENSE])
        assertEquals("eur", remembered[TransactionType.INCOME])
    }

    @Test fun `bill explicit account overrides expense preference and skip never remembers it`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "card"
        val vm = billVm()
        assertEquals("cash", vm.state.value.accountId)
        repo.addCategory("Extra", "#000000")
        vm.state.first { it.categories.any { category -> category.name == "Extra" } }
        assertEquals("cash", vm.state.value.accountId)
        vm.skip()
        vm.state.first { it.saved }
        assertEquals("card", remembered[TransactionType.EXPENSE])
    }

    @Test fun `receipt uses expense preference instead of income account and remembers successful save`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "cash"
        remembered[TransactionType.INCOME] = "card"
        val vm = seeded()
        vm.setType(TransactionType.INCOME)
        assertEquals("card", vm.state.value.accountId)
        vm.prefillReceipt(receipt(), null)
        assertEquals("cash", vm.state.value.accountId)
        vm.setAccount("card")
        vm.save()
        vm.state.first { it.saved }
        assertEquals("card", remembered[TransactionType.EXPENSE])
        assertEquals("card", remembered[TransactionType.INCOME])
    }

    @Test fun `receipt preserves explicit expense choice ahead of remembered account`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "card"
        val vm = seeded()
        vm.setAccount("cash")
        vm.prefillReceipt(receipt(), null)
        assertEquals("cash", vm.state.value.accountId)
    }

    @Test fun `receipt falls back from incompatible or archived expense preference to first RSD account`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "eur"
        val vm = seeded()
        vm.prefillReceipt(receipt(), null)
        assertEquals(vm.state.value.accounts.first { it.currency == Currency.RSD }.id, vm.state.value.accountId)
        assertEquals(Currency.RSD, vm.state.value.currency)

        remembered[TransactionType.EXPENSE] = "cash"
        repo.archiveAccount("cash")
        vm.state.first { it.accounts.none { account -> account.id == "cash" } }
        val reopened = newVm()
        reopened.prefillReceipt(receipt(), null)
        assertEquals("card", reopened.state.value.accountId)
        assertTrue(reopened.state.value.canSave)
    }

    @Test fun `receipt cannot save without RSD account and picks one when it becomes available`() = runTest(dispatcher) {
        val vm = seeded()
        repo.archiveAccount("cash")
        repo.archiveAccount("card")
        vm.state.first { it.accounts.size == 1 }
        vm.prefillReceipt(receipt(), null)
        assertEquals(null, vm.state.value.accountId)
        assertEquals(Currency.RSD, vm.state.value.currency)
        assertFalse(vm.state.value.canSave)
        repo.addAccount(Account("new", "New cash", Currency.RSD, AccountKind.SPENDING))
        vm.state.first { it.accountId == "new" }
        assertTrue(vm.state.value.canSave)
    }

    @Test fun `receipt account currency change triggers RSD fallback`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "cash"
        val vm = seeded()
        vm.prefillReceipt(receipt(), null)
        repo.updateAccount(vm.state.value.accounts.first { it.id == "cash" }.copy(currency = Currency.EUR))
        vm.state.first { it.accounts.any { account -> account.id == "cash" && account.currency == Currency.EUR } }
        assertEquals("card", vm.state.value.accountId)
        assertEquals(Currency.RSD, vm.state.value.currency)
        assertTrue(vm.state.value.canSave)
    }

    @Test fun `failed receipt persistence leaves preference unchanged`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "card"
        val vm = seeded()
        vm.prefillReceipt(receipt(), null)
        vm.setAccount("cash")
        driver.execute(null, "CREATE TRIGGER fail_tx BEFORE INSERT ON tx BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        vm.save()
        vm.state.first { it.saveError != null }
        assertEquals("card", remembered[TransactionType.EXPENSE])
        assertTrue(repo.transactions.first().isEmpty())
        assertFalse(repo.hasReceipt(receipt().id))
    }

    private fun paymentSlip() = NbsIpsParser.parse(
        "K:PR|V:01|C:1|R:200000000000000001|N:ЈП Пример\nНови Сад|I:RSD3018,58|SF:221|S:Uplata vode|RO:00000138")

    @Test fun `IPS prefills editable expense and saves once with note and ordinary transaction identity`() = runTest(dispatcher) {
        val vm = seeded()
        val slip = paymentSlip()
        vm.prefillPaymentSlip(slip)
        assertEquals("ЈП Пример", vm.state.value.merchant)
        assertEquals("3018.58", vm.state.value.amount.text)
        assertEquals(clock.today(), vm.state.value.date)
        assertNull(vm.state.value.categoryId)
        assertNull(vm.state.value.receipt)
        assertTrue(vm.state.value.typeLocked)
        vm.setType(TransactionType.INCOME)
        assertEquals(TransactionType.EXPENSE, vm.state.value.type)
        vm.setAccount("eur")
        assertFalse(vm.state.value.canSave)
        vm.setAccount("cash")
        vm.setCategory("cat-household")
        vm.setMerchant("Water company")
        vm.setDate(clock.today().minusDays(1))
        vm.setAmount("3020")
        vm.prefillPaymentSlip(slip)
        assertEquals("Water company", vm.state.value.merchant)
        assertEquals("3020", vm.state.value.amount.text)
        vm.save()
        vm.save()
        vm.state.first { it.saved }
        val tx = repo.transactions.first().single()
        assertEquals("Water company", tx.merchant)
        assertEquals("Uplata vode\n00 000138", tx.note)
        assertEquals("3020.00", tx.money.amount.toPlainString())
        assertEquals(clock.today().minusDays(1), tx.date)
        assertEquals("cat-household", tx.categoryId)
        assertEquals(TransactionSource.MANUAL, tx.source)
        assertFalse(tx.id.startsWith("receipt:"))
        assertNull(repo.receiptForTransaction(tx.id))
    }

    @Test fun `identical IPS can record a later payment and resetting clears all scan data`() = runTest(dispatcher) {
        val vm = seeded()
        vm.prefillPaymentSlip(paymentSlip())
        vm.save()
        vm.state.first { it.saved }
        val first = repo.transactions.first().single()
        vm.resetReceipt()
        assertNull(vm.state.value.paymentSlip)
        assertFalse(vm.state.value.scanned)
        assertEquals("", vm.state.value.merchant)
        assertFalse(vm.state.value.canSave)
        vm.prefillPaymentSlip(paymentSlip())
        vm.setDate(first.date.plusMonths(1))
        vm.save()
        vm.state.first { it.saved }
        val payments = repo.transactions.first()
        assertEquals(2, payments.size)
        assertEquals(2, payments.map { it.id }.distinct().size)
        assertTrue(payments.all { it.note == paymentSlip().note })
        vm.resetReceipt()
        vm.prefillReceipt(receipt(), null)
        assertNull(vm.state.value.paymentSlip)
    }

    @Test fun `IPS does not persist sensitive or partial drafts even when prefilled before loading`() = runTest(dispatcher) {
        seeded()
        val saved = SavedStateHandle(mapOf("draft.amount" to "99", "draft.merchant" to "Old manual draft"))
        val vm = AddViewModel(repo, clock, saved, accountStore).also { models += it }
        vm.prefillPaymentSlip(paymentSlip())
        vm.state.first { !it.loading }
        runCurrent()
        assertEquals("3018.58", vm.state.value.amount.text)
        assertEquals("ЈП Пример", vm.state.value.merchant)
        assertTrue(saved.keys().none { it.startsWith("draft.") })
        vm.setMerchant("Edited")
        runCurrent()
        assertTrue(saved.keys().none { it.startsWith("draft.") })
        val restored = AddViewModel(repo, clock, saved, accountStore).also { models += it }
        restored.state.first { !it.loading }
        assertNull(restored.state.value.paymentSlip)
        assertEquals("", restored.state.value.merchant)
        assertFalse(restored.state.value.canSave)
    }

    @Test fun `IPS save waits for an RSD account and rejects retired category`() = runTest(dispatcher) {
        val vm = seeded()
        repo.archiveAccount("cash")
        repo.archiveAccount("card")
        vm.state.first { it.accounts.size == 1 }
        vm.prefillPaymentSlip(paymentSlip())
        assertEquals(Currency.RSD, vm.state.value.currency)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(repo.transactions.first().isEmpty())
        repo.addAccount(Account("new", "New", Currency.RSD, AccountKind.SPENDING))
        vm.state.first { it.accountId == "new" }
        vm.setCategory("cat-household")
        repo.archiveCategory("cat-household")
        vm.state.first { s -> s.categories.none { it.id == "cat-household" } }
        assertFalse(vm.state.value.canSave)
        vm.setCategory(null)
        assertTrue(vm.state.value.canSave)
        vm.setMerchant(" ")
        assertFalse(vm.state.value.canSave)
    }

    @Test fun `failed IPS save retains details and retry records one expense`() = runTest(dispatcher) {
        val vm = seeded()
        vm.prefillPaymentSlip(paymentSlip())
        driver.execute(null, "CREATE TRIGGER fail_tx BEFORE INSERT ON tx BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        vm.save()
        vm.state.first { it.saveError != null }
        assertEquals(paymentSlip(), vm.state.value.paymentSlip)
        assertTrue(repo.transactions.first().isEmpty())
        driver.execute(null, "DROP TRIGGER fail_tx", 0)
        vm.save()
        vm.state.first { it.saved }
        assertEquals(paymentSlip().note, repo.transactions.first().single().note)
    }

    @Test fun `transfers preserve source flow and never change remembered accounts`() = runTest(dispatcher) {
        remembered[TransactionType.EXPENSE] = "cash"
        remembered[TransactionType.INCOME] = "eur"
        val vm = seeded()
        val previous = remembered.toMap()
        vm.setType(TransactionType.TRANSFER)
        assertEquals("cash", vm.state.value.accountId)
        vm.setAccount("card")
        vm.setToAccount("cash")
        vm.setAmount("100")
        vm.save()
        vm.state.first { it.saved }
        assertEquals(previous, remembered)
        val transaction = repo.transactions.first().single()
        assertEquals(TransactionType.TRANSFER, transaction.type)
        assertEquals("card", transaction.accountId)
        assertEquals("cash", transaction.toAccountId)
    }
}
