package app.kinjar.ui.add

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.R
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.FixedClock
import app.kinjar.data.Projector
import app.kinjar.data.TransactionAccountStore
import app.kinjar.data.testDb
import app.kinjar.domain.Account
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.Currency
import app.kinjar.domain.ExchangeQuote
import app.kinjar.domain.ExchangeRateSnapshot
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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

@OptIn(ExperimentalCoroutinesApi::class)
class TransferCurrencyTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val db = testDb()
    private val repo = BudgetRepository(db, EventStore(db, "dev-1", clock, Projector(db)), clock, "dev-1")
    private val accountStore = TransactionAccountStore({ null }, { _, _ -> })
    private val models = mutableListOf<ViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private suspend fun seed() = repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD), Account("card", "Card", Currency.RSD),
        Account("eur", "EUR", Currency.EUR), Account("eur2", "EUR 2", Currency.EUR), Account("usd", "USD", Currency.USD)))

    private suspend fun rates(eur: String, usd: String, day: LocalDate = clock.today()) =
        repo.exchangeRates.cache(mapOf(Currency.EUR to BigDecimal(eur), Currency.USD to BigDecimal(usd)), day)

    private suspend fun vm(handle: SavedStateHandle = SavedStateHandle()): AddViewModel =
        AddViewModel(repo, clock, handle, accountStore).also { models += it }
            .also { vm -> vm.state.first { it.accounts.isNotEmpty() && (it.editing != null || handle.get<String>("id") == null) } }

    private suspend fun AddViewModel.eurToRsd(amount: String) {
        setType(TransactionType.TRANSFER)
        setAccount("eur")
        setToAccount("cash")
        setAmount(amount)
    }

    @Test fun `NBS suggestion is labelled with its date, saved atomically and follows the sent amount`() = runTest(dispatcher) {
        seed()
        rates("117.2", "105", clock.today().minusDays(1))
        val vm = vm()
        vm.state.first { it.rates.cached.isNotEmpty() }
        vm.eurToRsd("10")
        var s = vm.state.value
        assertEquals(ReceivedSource.NBS, s.receivedSource)
        assertEquals(clock.today().minusDays(1), s.suggestion?.nbsDate)
        assertEquals(Money.of("1172", Currency.RSD), s.received)
        vm.setAmount(vm.state.value.amount.text + "0")
        s = vm.state.value
        assertEquals(Money.of("11720", Currency.RSD), s.received)
        assertTrue(s.canSave)
        vm.save()
        vm.state.first { it.saved }
        val saved = repo.transactions.first().single()
        assertEquals(Money.of("100", Currency.EUR), saved.money)
        assertEquals(Money.of("11720", Currency.RSD), saved.received)
    }

    @Test fun `manual received amount survives rate refresh and amount changes until the suggestion is chosen`() = runTest(dispatcher) {
        seed()
        rates("117", "105", clock.today().minusDays(1))
        val vm = vm()
        vm.state.first { it.rates.cached.isNotEmpty() }
        vm.eurToRsd("100")
        vm.setReceived("11650")
        assertEquals(ReceivedSource.MANUAL, vm.state.value.receivedSource)
        rates("118", "106")
        vm.state.first { it.rates.cached[Currency.EUR]?.rate?.compareTo(BigDecimal("118")) == 0 }
        vm.setAmount(vm.state.value.amount.text + "0")
        assertEquals(Money.of("11650", Currency.RSD), vm.state.value.received)
        vm.useSuggestedReceived()
        assertEquals(Money.of("118000", Currency.RSD), vm.state.value.received)
        vm.setReceived("11650,5")
        assertEquals(Money.of("11650.50", Currency.RSD), vm.state.value.received)
        vm.setReceived("1.234")
        assertNull(vm.state.value.received)
        assertFalse(vm.state.value.canSave)
    }

    @Test fun `override rate is never labelled NBS`() {
        val today = LocalDate.of(2026, 10, 14)
        val cached = mapOf(Currency.EUR to ExchangeQuote(BigDecimal("117"), today, Instant.EPOCH), Currency.USD to ExchangeQuote(BigDecimal("105"), today, Instant.EPOCH))
        val nbs = suggestReceived(Money.of("10", Currency.EUR), Currency.USD, ExchangeRateSnapshot(cached))
        assertEquals(today, nbs?.nbsDate)
        assertEquals(Money.of("11.14", Currency.USD), nbs?.money)
        val overridden = suggestReceived(Money.of("10", Currency.EUR), Currency.RSD, ExchangeRateSnapshot(cached, mapOf(Currency.EUR to BigDecimal("120"))))
        assertNull(overridden?.nbsDate)
        assertEquals(Money.of("1200", Currency.RSD), overridden?.money)
        assertNull(suggestReceived(Money.of("10", Currency.EUR), Currency.RSD, ExchangeRateSnapshot()))
    }

    @Test fun `missing rate needs a typed amount, then saves`() = runTest(dispatcher) {
        seed()
        val vm = vm()
        vm.eurToRsd("50")
        assertEquals(ReceivedSource.MISSING, vm.state.value.receivedSource)
        assertFalse(vm.state.value.canSave)
        vm.setReceived("5850")
        assertTrue(vm.state.value.canSave)
        vm.save()
        vm.state.first { it.saved }
        assertEquals(Money.of("5850", Currency.RSD), repo.transactions.first().single().received)
    }

    @Test fun `currency change drops a typed amount with a notice while same-currency transfers keep old behaviour`() = runTest(dispatcher) {
        seed()
        val vm = vm()
        vm.eurToRsd("50")
        vm.setReceived("5850")
        vm.setToAccount("eur2")
        assertFalse(vm.state.value.crossCurrency)
        assertNull(vm.state.value.received)
        vm.setToAccount("cash")
        assertEquals(Money.of("5850", Currency.RSD), vm.state.value.received)
        vm.setToAccount("usd")
        val s = vm.state.value
        assertTrue(s.receivedReset)
        assertFalse(s.receivedManual)
        assertNull(s.received)
        vm.setAccount("card")
        vm.setToAccount("cash")
        assertTrue(vm.state.value.canSave)
        vm.save()
        vm.state.first { it.saved }
        assertNull(repo.transactions.first().single().received)
    }

    @Test fun `type change clears the received amount`() = runTest(dispatcher) {
        seed()
        val vm = vm()
        vm.eurToRsd("50")
        vm.setReceived("5850")
        vm.setType(TransactionType.INCOME)
        vm.setType(TransactionType.TRANSFER)
        vm.setAccount("eur")
        vm.setToAccount("cash")
        assertTrue(vm.state.value.crossCurrency)
        assertFalse(vm.state.value.receivedManual)
        assertNull(vm.state.value.received)
    }

    @Test fun `editing a stored transfer keeps its received amount instead of today's rate`() = runTest(dispatcher) {
        seed()
        repo.add(Transaction("t", TransactionType.TRANSFER, Money.of("100", Currency.EUR), "eur", "cash", null, clock.today(), "", "", "m",
            TransactionSource.MANUAL, received = Money.of("11500", Currency.RSD)))
        rates("120", "106")
        val handle = SavedStateHandle(mapOf("id" to "t"))
        val vm = vm(handle)
        vm.state.first { it.rates.cached.isNotEmpty() }
        assertEquals(ReceivedSource.SAVED, vm.state.value.receivedSource)
        assertEquals(Money.of("11500", Currency.RSD), vm.state.value.received)
        advanceUntilIdle()
        assertTrue(handle.keys().none { it.startsWith("draft.") })
        vm.setMerchant("Exchange")
        vm.save()
        vm.state.first { it.saved }
        val saved = repo.transactions.first().single()
        assertEquals("Exchange", saved.merchant)
        assertEquals(Money.of("11500", Currency.RSD), saved.received)
    }

    @Test fun `typed received amount survives process recreation and a corrupt one is dropped`() = runTest(dispatcher) {
        seed()
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.eurToRsd("50")
        first.setReceived("5850.5")
        advanceUntilIdle()
        val restored = vm(handle).state.value
        assertEquals(TransactionType.TRANSFER, restored.type)
        assertEquals(Money.of("5850.50", Currency.RSD), restored.received)
        assertTrue(restored.canSave)

        val tampered = SavedStateHandle(mapOf("draft.type" to "TRANSFER", "draft.amount" to "50", "draft.account" to "eur", "draft.to" to "cash",
            "draft.received" to "5x", "draft.receivedPair" to "EUR>RSD"))
        val sanitized = vm(tampered).state.value
        assertFalse(sanitized.receivedManual)
        assertEquals(ReceivedSource.MISSING, sanitized.receivedSource)
    }

    @Test fun `a received amount changed on another phone makes the open correction stale`() = runTest(dispatcher) {
        seed()
        val stored = Transaction("t", TransactionType.TRANSFER, Money.of("100", Currency.EUR), "eur", "cash", null, clock.today(), "", "", "m",
            TransactionSource.MANUAL, received = Money.of("11500", Currency.RSD))
        repo.add(stored)
        val vm = vm(SavedStateHandle(mapOf("id" to "t")))
        repo.edit(stored.copy(received = Money.of("11600", Currency.RSD)), expected = stored)
        vm.setReceived("11400")
        vm.save()
        val s = vm.state.first { it.stale }
        assertEquals(R.string.audit_edit_stale, s.saveError)
        assertEquals(Money.of("11600", Currency.RSD), repo.transactions.first().single().received)
    }

    @Test fun `income uses income categories and type switches restore each choice`() = runTest(dispatcher) {
        seed()
        repo.addCategory("Side gig", "#000000", kind = CategoryKind.INCOME)
        val vm = vm()
        val salary = vm.state.first { s -> s.categories.any { it.name == "Side gig" } }.categories.first { it.name == "Side gig" }.id
        vm.setCategory("cat-household")
        vm.setType(TransactionType.INCOME)
        var s = vm.state.value
        assertNull(s.categoryId)
        assertTrue(s.pickableCategories.isNotEmpty() && s.pickableCategories.all { it.kind == CategoryKind.INCOME })
        assertTrue(s.pickableCategories.any { it.id == salary })
        vm.setAmount("1")
        assertTrue(vm.state.value.canSave)
        vm.setCategory("cat-household")
        assertFalse(vm.state.value.canSave)
        vm.setCategory(salary)
        assertTrue(vm.state.value.canSave)
        vm.setType(TransactionType.EXPENSE)
        assertEquals("cat-household", vm.state.value.categoryId)
        vm.setType(TransactionType.TRANSFER)
        assertNull(vm.state.value.categoryId)
        vm.setType(TransactionType.INCOME)
        s = vm.state.value
        assertEquals(salary, s.categoryId)
        vm.save()
        vm.state.first { it.saved }
        val saved = repo.transactions.first().single()
        assertEquals(TransactionType.INCOME, saved.type)
        assertEquals(salary, saved.categoryId)
    }
}
