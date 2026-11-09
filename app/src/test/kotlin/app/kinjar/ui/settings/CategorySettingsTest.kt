package app.kinjar.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import app.kinjar.data.StarterCategories
import app.kinjar.data.TransactionAccountStore
import app.kinjar.data.testDb
import app.kinjar.domain.Account
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.Currency
import app.kinjar.ui.CategoryIconCatalog
import app.kinjar.ui.CategoryPalette
import app.kinjar.ui.IncomeStarterNames
import app.kinjar.ui.categoryIconKey
import app.kinjar.ui.categoryLabel
import app.kinjar.ui.defaultIconKey
import app.kinjar.ui.isDuplicateCategoryName
import app.kinjar.ui.loans.LoanDetailViewModel
import app.kinjar.ui.starterRank
import app.kinjar.ui.starterSeedName
import app.kinjar.ui.unusedPaletteColor
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CategorySettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val db = testDb()
    private val repo = BudgetRepository(db, EventStore(db, "dev-1", clock, Projector(db)), clock, "dev-1")
    private val models = mutableListOf<ViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private suspend fun setUpFamily() = repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
    private fun editor(id: String? = null, kind: CategoryKind = CategoryKind.EXPENSE) =
        EditCategoryViewModel(repo, SavedStateHandle(listOfNotNull(id?.let { "id" to it }, "kind" to kind.name).toMap())).also { models += it }

    @Test
    fun `palette has twenty unique swatches each with its own spoken name`() {
        assertEquals(20, CategoryPalette.size)
        assertEquals(CategoryPalette.size, CategoryPalette.map { it.uppercase() }.toSet().size)
        assertTrue(CategoryPalette.all { Regex("#[0-9A-Fa-f]{6}").matches(it) })
        assertEquals(CategoryPalette.size, PaletteNames.size)
        assertEquals(PaletteNames.size, PaletteNames.toSet().size)
    }

    @Test
    fun `existing starter colors stay in the palette and Household and Other keep the shared gray`() {
        StarterCategories.all.filter { it.kind == CategoryKind.EXPENSE }.forEach { starter -> assertTrue(starter.id, CategoryPalette.any { it.equals(starter.color, ignoreCase = true) }) }
        assertEquals("#6E747D", StarterCategories.all.single { it.id == "cat-household" }.color)
        assertEquals("#6E747D", StarterCategories.all.single { it.id == "cat-other" }.color)
    }

    @Test
    fun `new categories get an unused color and reuse starts only when every swatch is taken`() {
        assertEquals(CategoryPalette[2], unusedPaletteColor(listOf(CategoryPalette[0], CategoryPalette[1].lowercase())))
        assertEquals(CategoryPalette.first(), unusedPaletteColor(CategoryPalette))
    }

    @Test
    fun `icon keys are unique and starters resolve to their own icon while unknown keys fall back`() {
        assertEquals(CategoryIconCatalog.size, CategoryIconCatalog.map { it.key }.toSet().size)
        assertEquals(CategoryIconCatalog.size, CategoryIconCatalog.map { it.label }.toSet().size)
        val keys = CategoryIconCatalog.map { it.key }.toSet()
        listOf("cart", "receipt", "car", "restaurant", "home", "child", "celebration", "health", "more", "work", "star", "laptop", "gift", "percent")
            .forEach { assertTrue(it, it in keys) }
        assertEquals("cart", categoryIconKey(null, "cat-groceries", CategoryKind.EXPENSE))
        assertEquals("work", categoryIconKey(null, "cat-income-salary", CategoryKind.INCOME))
        assertEquals("gift", categoryIconKey("gift", "cat-groceries", CategoryKind.EXPENSE))
        assertEquals("cart", categoryIconKey("from-a-newer-build", "cat-groceries", CategoryKind.EXPENSE))
        assertEquals(defaultIconKey("user-made", CategoryKind.INCOME), categoryIconKey("from-a-newer-build", "user-made", CategoryKind.INCOME))
        assertTrue(defaultIconKey(null, CategoryKind.EXPENSE) in keys)
        assertTrue(defaultIconKey(null, CategoryKind.INCOME) in keys)
        (StarterCategories.all.map { it.id } + IncomeStarterNames.keys).forEach { assertTrue(it, categoryIconKey(null, it, CategoryKind.EXPENSE) in keys) }
    }

    @Test
    fun `every starter has a translated label, seeded name and stable rank`() {
        (StarterCategories.all.map { it.id } + IncomeStarterNames.keys).distinct().forEachIndexed { index, id ->
            assertTrue(id, categoryLabel(id) != null)
            assertTrue(id, starterSeedName(id) != null)
            assertEquals(id, index, starterRank(id))
        }
        assertEquals(Int.MAX_VALUE, starterRank("user-made"))
        StarterCategories.all.forEach { assertEquals(it.name, starterSeedName(it.id)) }
    }

    @Test
    fun `duplicate names compare trimmed and case insensitive`() {
        assertTrue(isDuplicateCategoryName("  groceries ", listOf("Groceries")))
        assertFalse(isDuplicateCategoryName("Groceries 2", listOf("Groceries")))
        assertFalse(isDuplicateCategoryName("   ", listOf("")))
    }

    @Test
    fun `list groups categories by kind`() = runTest(dispatcher) {
        setUpFamily()
        repo.addCategory("Rent income", CategoryPalette[12], "bank", CategoryKind.INCOME)
        val list = CategoriesViewModel(repo).also { models += it }
        val collector = backgroundScope.launch { list.categories.collect {} }
        val groups = list.categories.filterNotNull().first { g -> g.income.any { it.name == "Rent income" } }
        assertTrue(groups.expense.all { it.kind == CategoryKind.EXPENSE })
        assertTrue(groups.income.all { it.kind == CategoryKind.INCOME })
        val starters = StarterCategories.all.filter { it.kind == CategoryKind.EXPENSE }.map { it.id }
        assertEquals(starters, groups.expense.map { it.id }.filter { it in starters })
        collector.cancel()
    }

    @Test
    fun `new income category saves its kind, icon and unused color`() = runTest(dispatcher) {
        setUpFamily()
        val used = repo.categories.first().map { it.color.uppercase() }.toSet()
        val vm = editor(kind = CategoryKind.INCOME)
        vm.state.first { it.others.isNotEmpty() }
        assertFalse(vm.state.value.color.uppercase() in used)
        assertEquals(CategoryKind.INCOME, vm.state.value.kind)
        vm.setName("  Rent income ")
        vm.setIcon("bank")
        vm.save()
        vm.state.first { it.done }
        val saved = repo.categories.first().single { it.name == "Rent income" }
        assertEquals(CategoryKind.INCOME, saved.kind)
        assertEquals("bank", saved.icon)
        assertEquals(vm.state.value.color, saved.color)
    }

    @Test
    fun `kind is fixed while editing and an untouched icon stays unset`() = runTest(dispatcher) {
        setUpFamily()
        val vm = editor("cat-groceries")
        vm.state.first { it.loaded }
        assertNull(vm.state.value.icon)
        vm.setKind(CategoryKind.INCOME)
        assertEquals(CategoryKind.EXPENSE, vm.state.value.kind)
        vm.setName("Food")
        vm.save()
        vm.state.first { it.done }
        val saved = repo.categories.first().single { it.id == "cat-groceries" }
        assertEquals("Food", saved.name)
        assertEquals(CategoryKind.EXPENSE, saved.kind)
        assertNull(saved.icon)

        repo.addCategory("Side job", CategoryPalette[13], null, CategoryKind.INCOME)
        val incomeId = repo.categories.first().single { it.name == "Side job" }.id
        val income = editor(incomeId)
        income.state.first { it.loaded }
        income.setKind(CategoryKind.EXPENSE)
        income.setIcon("laptop")
        income.save()
        income.state.first { it.done }
        val edited = repo.categories.first().single { it.id == incomeId }
        assertEquals(CategoryKind.INCOME, edited.kind)
        assertEquals("laptop", edited.icon)
    }

    @Test
    fun `duplicate names within a kind block saving and another kind may reuse the name`() = runTest(dispatcher) {
        setUpFamily()
        val vm = editor()
        vm.state.first { it.others.isNotEmpty() }
        vm.setName(" groceries ")
        assertTrue(vm.state.value.nameTaken)
        assertFalse(vm.state.value.canSave)
        vm.setKind(CategoryKind.INCOME)
        assertFalse(vm.state.value.nameTaken)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `a duplicate the repository rejects shows the friendly error instead of failing`() = runTest(dispatcher) {
        setUpFamily()
        val vm = editor()
        vm.state.first { it.others.isNotEmpty() }
        repo.addCategory("Pets", CategoryPalette[14])
        vm.setName("pets")
        vm.save()
        vm.state.first { it.duplicate }
        vm.writes.state.first { !it.busy }
        assertFalse(vm.writes.state.value.failed)
        assertFalse(vm.state.value.done)
        assertEquals(1, repo.categories.first().count { it.name.equals("pets", ignoreCase = true) })
        vm.setName("Pet food")
        assertFalse(vm.state.value.duplicate)
    }

    @Test
    fun `bills and loan payments offer expense categories only`() = runTest(dispatcher) {
        setUpFamily()
        repo.addCategory("Rent income", CategoryPalette[12], null, CategoryKind.INCOME)
        val bill = EditRecurringViewModel(repo, clock, SavedStateHandle()).also { models += it }
        val categories = bill.state.first { it.loaded }.categories
        assertTrue(categories.isNotEmpty())
        assertTrue(categories.all { it.kind == CategoryKind.EXPENSE })

        val loan = LoanDetailViewModel(repo, clock, SavedStateHandle(mapOf("id" to "l")), TransactionAccountStore({ null }, { _, _ -> })).also { models += it }
        val loanCategories = loan.data.filterNotNull().first().categories
        assertTrue(loanCategories.isNotEmpty())
        assertTrue(loanCategories.all { it.kind == CategoryKind.EXPENSE })
    }
}
