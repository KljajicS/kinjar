package app.kinjar.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import app.kinjar.data.*
import app.kinjar.data.db.KinjarDb
import app.kinjar.data.rates.*
import app.kinjar.domain.*
import app.kinjar.ui.home.HomeViewModel
import app.kinjar.ui.goals.GoalsViewModel
import app.kinjar.ui.insights.InsightsViewModel
import app.kinjar.ui.settings.AccountsViewModel
import app.kinjar.ui.settings.ExchangeRatesViewModel
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ExchangeRatesViewModelTest {
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private fun <T : androidx.lifecycle.ViewModel> owned(viewModel: T): T = viewModel.also { viewModels += it }
    private val dispatcher = StandardTestDispatcher()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = KinjarDb(driver).also { KinjarDb.Schema.create(driver) }
    private val clock = FixedClock(Instant.parse("2026-10-15T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val repo = BudgetRepository(db, EventStore(db, "device", clock, Projector(db)), clock)
    private val rates = repo.exchangeRates

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = runTest(dispatcher) {
        viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        driver.close()
        Dispatchers.resetMain()
    }

    @Test fun `stale warning starts at seven days and manual overrides exempt only their currency`() = runTest(dispatcher) {
        rates.cache(mapOf(Currency.EUR to BigDecimal("117"), Currency.USD to BigDecimal("105")), clock.today())
        val initial = repo.financial.first()
        assertFalse(RateStatus.from(initial).estimated)
        val foreignAccount = Account("eur", "Euro", Currency.EUR, AccountKind.SPENDING)
        assertTrue(RateStatus.from(initial.copy(accounts = listOf(foreignAccount))).estimated)
        assertFalse(RateStatus.from(initial.copy(accounts = listOf(foreignAccount.copy(currency = Currency.RSD)))).estimated)
        assertFalse(RateStatus.from(initial.copy(day = initial.day.plusDays(6))).stale)
        val weekLater = initial.copy(day = initial.day.plusDays(7))
        assertTrue(RateStatus.from(weekLater).stale)
        val oneOverride = initial.exchange.copy(overrides = mapOf(Currency.EUR to BigDecimal("118")))
        assertTrue(RateStatus.from(weekLater.copy(exchange = oneOverride)).stale)
        val bothOverrides = oneOverride.copy(overrides = oneOverride.overrides + (Currency.USD to BigDecimal("106")))
        assertFalse(RateStatus.from(weekLater.copy(exchange = bothOverrides)).stale)
    }

    @Test fun `all four tabs react and every emission uses one rate including historical months`() = runTest(dispatcher) {
        repo.setUp("Family", listOf(Account("spend", "Spending", Currency.EUR, AccountKind.SPENDING), Account("save", "Savings", Currency.EUR, AccountKind.SAVINGS)))
        suspend fun add(id: String, type: TransactionType, amount: String, account: String, previous: Boolean = false) {
            val category = if (type == TransactionType.INCOME) "cat-income-salary" else "cat-groceries"
            repo.add(Transaction(id, type, Money.of(amount, Currency.EUR), account, null, category,
                if (previous) clock.today().minusMonths(1) else clock.today(), "", "", "member", TransactionSource.MANUAL))
        }
        add("income", TransactionType.INCOME, "1000", "spend")
        add("savings", TransactionType.INCOME, "100", "save")
        add("expense", TransactionType.EXPENSE, "10", "spend")
        add("old", TransactionType.EXPENSE, "3", "spend", true)
        repo.setRule(RecurringRule("bill", "Bill", Money.of("20", Currency.EUR), "spend", "cat-bills", Cadence.MONTHLY, clock.today().plusDays(5)))
        repo.setGoal(Goal("goal", "Goal", Money.of("2000", Currency.RSD)))
        val original = db.txQueries.all().executeAsList()
        val home = owned(HomeViewModel(repo, clock))
        val accounts = owned(AccountsViewModel(repo))
        val goals = owned(GoalsViewModel(repo, clock))
        val insights = owned(InsightsViewModel(repo, clock))
        fun <T : Any> observe(state: StateFlow<T?>, verify: (T) -> Unit): Channel<T> {
            val channel = Channel<T>(Channel.UNLIMITED)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                state.filterNotNull().collect { verify(it); channel.send(it) }
            }
            return channel
        }
        fun expected(amount: String, status: RateStatus) = Money.of(BigDecimal(amount).multiply(status.snapshot.rates.rate(Currency.EUR) ?: BigDecimal.ZERO), Currency.RSD)
        fun known(status: RateStatus) = status.snapshot.rates.rate(Currency.EUR) != null
        val homes = observe(home.state) { s ->
            if (known(s.rateStatus)) {
                assertEquals(expected("1087", s.rateStatus), s.summary.savingsRsd)
                assertEquals(Money.of("1087", Currency.EUR), s.summary.savingsEur)
                assertEquals(expected("1090", s.rateStatus), s.summary.difference)
            } else assertNull(s.summary.savingsRsd)
        }
        val accountStates = observe(accounts.state) { s ->
            assertEquals(listOf(Money.of("987", Currency.EUR), Money.of("100", Currency.EUR)), s.accounts.map { it.balance })
        }
        val goalStates = observe(goals.state) { s ->
            assertEquals(if (known(s.rateStatus)) Money.of("2000", Currency.RSD) else null, s.goals.single().funded)
        }
        val insightStates = observe(insights.state) { s ->
            assertEquals(expected(if (s.summary.month == YearMonth.from(clock.today())) "10" else "3", s.rateStatus), s.summary.spent)
        }
        assertTrue(homes.receive().summary.monthPartial)
        assertEquals(setOf(Currency.EUR), accountStates.receive().rateStatus.missing)
        goalStates.receive()
        insightStates.receive()
        suspend fun <T> awaitRate(channel: Channel<T>, rate: BigDecimal, status: (T) -> RateStatus) {
            do { val value = channel.receive() } while (status(value).snapshot.rates.rate(Currency.EUR)?.compareTo(rate) != 0)
        }
        rates.cache(mapOf(Currency.EUR to BigDecimal("117"), Currency.USD to BigDecimal("105")), clock.today())
        for (rate in listOf("117", "120", "119", "117")) {
            when (rate) { "120", "119" -> rates.setOverride(Currency.EUR, rate); "117" -> rates.setOverride(Currency.EUR, null) }
            val decimal = rate.toBigDecimal()
            awaitRate(homes, decimal) { it.rateStatus }
            awaitRate(accountStates, decimal) { it.rateStatus }
            awaitRate(goalStates, decimal) { it.rateStatus }
            awaitRate(insightStates, decimal) { it.rateStatus }
        }
        insights.previousMonth()
        insights.state.first { it?.summary?.month == YearMonth.from(clock.today()).minusMonths(1) }
        rates.setOverride(Currency.EUR, "125")
        val historical = insights.state.first { it?.rateStatus?.snapshot?.rates?.rate(Currency.EUR) == BigDecimal("125") }!!
        assertEquals(Money.of("375", Currency.RSD), historical.summary.spent)
        assertEquals(original, db.txQueries.all().executeAsList())
    }

    @Test fun `failed manual write retains draft and double tap writes once on retry`() = runTest(dispatcher) {
        val attempts = object : RateAttempts { override var lastAttempt: Instant? = null }
        val refresh = RateRefresh(rates, RateLookup { error("offline") }, attempts, clock)
        val vm = owned(ExchangeRatesViewModel(rates, refresh))
        vm.setInput(Currency.EUR, "117,12345678")
        driver.execute(null, "CREATE TRIGGER fail_rate BEFORE INSERT ON exchange_rate_override BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        vm.save(Currency.EUR)
        vm.writes.state.first { it.failed }
        assertEquals("117,12345678", vm.inputs.value[Currency.EUR])
        assertEquals(0L, db.eventQueries.count().executeAsOne())
        driver.execute(null, "DROP TRIGGER fail_rate", 0)
        vm.save(Currency.EUR)
        vm.save(Currency.EUR)
        vm.writes.state.first { !it.busy && !it.failed }
        assertNull(vm.inputs.value[Currency.EUR])
        assertEquals(1L, db.eventQueries.count().executeAsOne())
        assertEquals(BigDecimal("117.12345678"), rates.snapshots.first().rates.rate(Currency.EUR))
        vm.reset(Currency.EUR)
        vm.writes.state.first { !it.busy && !it.failed }
        assertNull(rates.snapshots.first().rates.rate(Currency.EUR))
    }

    @Test fun `failed snapshot read can retry without losing rate draft`() = runTest(dispatcher) {
        rates.setOverride(Currency.EUR, "117")
        driver.execute(null, "UPDATE exchange_rate_override SET rate = 'invalid'", 0)
        val attempts = object : RateAttempts { override var lastAttempt: Instant? = null }
        val vm = owned(ExchangeRatesViewModel(rates, RateRefresh(rates, RateLookup { error("offline") }, attempts, clock)))
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.snapshot.collect() }
        try {
            vm.setInput(Currency.EUR, "118,5")
            vm.loadFailed.first { it }
            assertNull(vm.snapshot.value)
            driver.execute(null, "UPDATE exchange_rate_override SET rate = '117'", 0)
            vm.retryLoad()
            val recovered = vm.snapshot.filterNotNull().first()
            assertEquals(BigDecimal("117"), recovered.rates.rate(Currency.EUR))
            assertFalse(vm.loadFailed.value)
            assertEquals("118,5", vm.inputs.value[Currency.EUR])
        } finally {
            subscription.cancelAndJoin()
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
