package app.kinjar.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

import androidx.lifecycle.SavedStateHandle
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.FixedClock
import app.kinjar.data.Projector
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.Account
import app.kinjar.domain.AccountKind
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.ui.home.HomeViewModel
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = KinjarDb(driver).also { KinjarDb.Schema.create(driver) }
    private val repo = BudgetRepository(db, EventStore(db, "dev-1", clock, Projector(db)), clock, "dev-1")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    private val models = mutableListOf<ViewModel>()

    @Test fun `changing add visibility writes only local preferences`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
        val before = repo.accounts.first()
        val events = db.eventQueries.count().executeAsOne()
        val preferences = app.kinjar.data.AccountAppearanceStore()
        val vm = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to "cash")), preferences).also { models += it }
        vm.state.first { it.balance != null }
        vm.setShowInAdd(false)
        vm.setFavorite(true)
        assertEquals(false, vm.state.value.appearance.favorite)
        vm.save()
        vm.state.first { it.done }
        assertEquals(false, preferences.state.value.appearance("cash").showInAdd)
        assertEquals(before, repo.accounts.first())
        assertEquals(events, db.eventQueries.count().executeAsOne())
    }

    @Test fun `changing only color also publishes legacy local icon`() = runTest(dispatcher) {
        val account = Account("cash", "Cash", Currency.RSD)
        repo.setUp("S", listOf(account))
        val preferences = app.kinjar.data.AccountAppearanceStore()
        preferences.save("cash", app.kinjar.data.AccountAppearance(icon = app.kinjar.data.AccountSymbol.WALLET), listOf(account))
        val vm = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to "cash")), preferences).also { models += it }
        vm.state.first { it.balance != null }
        vm.setColor(app.kinjar.data.AccountTint.BLUE)
        vm.save()
        vm.state.first { it.done }
        val saved = repo.accounts.first().single()
        assertEquals("WALLET", saved.icon)
        assertEquals("BLUE", saved.color)
    }

    @Test fun `favorite icon and color save in account settings and reload`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD), Account("card", "Card", Currency.RSD),
            Account("eur", "Euro", Currency.EUR), Account("extra", "Extra", Currency.RSD)))
        val preferences = app.kinjar.data.AccountAppearanceStore()
        val vm = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to "cash")), preferences).also { models += it }
        vm.state.first { it.balance != null }
        assertEquals(true, vm.state.value.appearance.favorite)
        vm.setFavorite(false)
        vm.setIcon(app.kinjar.data.AccountSymbol.WALLET)
        vm.setColor(app.kinjar.data.AccountTint.GREEN)
        vm.save()
        vm.state.first { it.done }
        assertEquals("WALLET", repo.accounts.first().first { it.id == "cash" }.icon)
        assertEquals("GREEN", repo.accounts.first().first { it.id == "cash" }.color)
        val reloaded = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to "cash")), preferences).also { models += it }
        reloaded.state.first { it.balance != null }
        assertEquals(app.kinjar.data.AccountAppearance(app.kinjar.data.AccountSymbol.WALLET, app.kinjar.data.AccountTint.GREEN, false), reloaded.state.value.appearance)
        val extra = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to "extra")), preferences).also { models += it }
        extra.state.first { it.balance != null && it.canFavorite }
        extra.setFavorite(true)
        extra.save()
        extra.state.first { it.done }
        assertEquals(listOf("card", "eur", "extra"), preferences.state.value.favorites(repo.accounts.first()).map { it.id })
    }
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    @Test
    fun `account exclusion can be created loaded and switched off`() = runTest(dispatcher) {
        val create = EditAccountViewModel(repo, SavedStateHandle()).also { models += it }
        assertEquals(false, create.state.value.excludedFromGoals)
        create.setName("Separate savings")
        create.setExcludedFromGoals(true)
        create.save()
        create.state.first { it.done }
        val account = repo.accounts.first().single()
        assertEquals(true, account.excludedFromGoals)
        val edit = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to account.id))).also { models += it }
        edit.state.first { it.balance != null }
        assertEquals(true, edit.state.value.excludedFromGoals)
        edit.setExcludedFromGoals(false)
        edit.save()
        edit.state.first { it.done }
        assertEquals(false, repo.accounts.first().single().excludedFromGoals)
    }

    @Test
    fun `reorder is shared with home and a failed write keeps the stored order`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD), Account("card", "Card", Currency.RSD), Account("eur", "Euro", Currency.EUR)))
        val vm = AccountsViewModel(repo).also { models += it }
        vm.state.filterNotNull().first { it.accounts.size == 3 }
        vm.reorder(listOf("eur", "cash", "card"))
        vm.state.filterNotNull().first { s -> s.accounts.map { it.account.id } == listOf("eur", "cash", "card") }
        HomeViewModel(repo, clock).also { models += it }.state.filterNotNull().first { s -> s.accounts.map { it.account.id } == listOf("eur", "cash", "card") }

        driver.execute(null, "CREATE TRIGGER fail_order BEFORE INSERT ON event BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        vm.reorder(listOf("card", "cash", "eur"))
        vm.writes.state.first { it.failed }
        val s = vm.state.filterNotNull().first { it.reorderFailures == 1 }
        assertEquals(listOf("eur", "cash", "card"), s.accounts.map { it.account.id })
    }

    @Test
    fun `account editor read failure can be retried without crashing`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
        driver.execute(null, "ALTER TABLE tx RENAME TO tx_hidden", 0)
        val vm = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to "cash"))).also { models += it }
        vm.state.first { it.loadFailed }
        driver.execute(null, "ALTER TABLE tx_hidden RENAME TO tx", 0)
        vm.retryLoad()
        val state = vm.state.first { !it.loadFailed && it.balance != null }
        assertEquals("Cash", state.name)
    }

    @Test
    fun `recurring shared read failure reaches retry screen`() = runTest(dispatcher) {
        val shared = BudgetRepository(db, EventStore(db, "dev-1", clock, Projector(db)), clock, "dev-1", backgroundScope)
        driver.execute(null, "ALTER TABLE tx RENAME TO tx_hidden", 0)
        val vm = RecurringViewModel(shared).also { models += it }
        val collector = backgroundScope.launch { vm.state.collect {} }
        vm.failed.first { it }
        driver.execute(null, "ALTER TABLE tx_hidden RENAME TO tx", 0)
        vm.retry()
        assertEquals(emptyList<app.kinjar.domain.Bill>(), vm.state.filterNotNull().first().bills)
        collector.cancel()
    }

    @Test
    fun `editing keeps the legacy kind and archived accounts leave the list`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("sav", "Savings", Currency.RSD, AccountKind.SAVINGS), Account("cash", "Cash", Currency.RSD)))
        val edit = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to "sav"))).also { models += it }
        edit.state.first { it.name == "Savings" && it.balance != null }
        assertEquals(Money.zero(Currency.RSD), edit.state.value.balance)
        edit.setName("Rainy day")
        edit.save()
        edit.state.first { it.done }
        assertEquals(AccountKind.SAVINGS, repo.accounts.first().first { it.id == "sav" }.kind)

        repo.archiveAccount("sav")
        val s = AccountsViewModel(repo).also { models += it }.state.filterNotNull().first { it.accounts.size == 1 }
        assertEquals("cash", s.accounts.single().account.id)
    }
}
