package app.kinjar.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.Account
import app.kinjar.domain.AccountCreated
import app.kinjar.domain.Category
import app.kinjar.domain.CategoryCreated
import app.kinjar.domain.Currency
import app.kinjar.domain.Goal
import app.kinjar.domain.Money
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCategory
import app.kinjar.domain.ProjectCost
import app.kinjar.domain.Rates
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionAdded
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProjectRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val store = EventStore(db, "dev-1", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "dev-1")
    private val projects = ProjectRepository(db, store, clock)
    private val day = LocalDate.of(2026, 10, 10)
    private val nbs = mapOf(Currency.EUR to "117.17")
    private lateinit var member: String
    private lateinit var materials: String
    private lateinit var kitchen: Project

    private fun rsd(value: String) = Money.of(value, Currency.RSD)
    private fun eur(value: String) = Money.of(value, Currency.EUR)

    private suspend fun seed() {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD), Account("eur", "Euro", Currency.EUR)))
        member = repo.currentMember()!!.id
        repo.addCategory("Materials", "#112233")
        materials = repo.categories.first().first { it.name == "Materials" }.id
        repo.add(Transaction("income", TransactionType.INCOME, rsd("200000"), "cash", null, null, day, "", "", member, TransactionSource.MANUAL))
        kitchen = Project("kitchen", "Kitchen", Currency.EUR, eur("5000"), setOf(materials))
        projects.save(kitchen)
    }

    private suspend fun expense(id: String, money: Money, category: String = materials, date: LocalDate = day): Transaction =
        Transaction(id, TransactionType.EXPENSE, money, if (money.currency == Currency.EUR) "eur" else "cash", null, category, date,
            "", "", member, TransactionSource.MANUAL).also { repo.add(it) }

    private fun ProjectCost.into(currency: Currency): ProjectCost {
        val same = original.currency == currency
        val converted = Rates(nbs.mapValues { it.value.toBigDecimal() }).convert(original, currency)!!
        return copy(converted = converted, ratesToRsd = if (same) emptyMap() else nbs, rateDate = date,
            rateSource = if (same) "same-currency" else "NBS middle rate")
    }

    private fun manual(id: String, original: Money, project: Project = kitchen, date: LocalDate = day, category: String? = null) =
        ProjectCost(id, project.id, category, original, original, date, "").into(project.currency)

    private fun link(id: String, tx: Transaction, project: Project = kitchen) =
        ProjectCost(id, project.id, null, tx.money, tx.money, tx.date, "", tx.id).into(project.currency)

    private suspend fun refused(message: String, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull() ?: return fail("Expected: $message")
        assertEquals(message, failure.message)
    }

    @Test fun `past costs and linked expenses never change accounts, Home or Goals`() = runTest {
        seed()
        repo.setGoal(Goal("goal", "Reserve", rsd("500000")))
        val tiles = expense("tiles", rsd("23434"))
        val before = repo.financial.first()
        projects.record(listOf(manual("past", rsd("117170"), date = LocalDate.of(2025, 3, 1)), link("linked", tiles)))
        val after = repo.financial.first()
        assertEquals(before.home, after.home)
        assertEquals(before.goals, after.goals)
        assertEquals(before.transactions, after.transactions)
        assertEquals(listOf(eur("200.00"), eur("1000.00")), projects.readBook().costsFor("kitchen").map { it.converted })
        refused("Cost date is in the future") { projects.record(listOf(manual("later", rsd("1"), date = day.plusDays(5)))) }
    }

    @Test fun `an expense links once across all projects, archived included, and a failing batch writes nothing`() = runTest {
        seed()
        val first = expense("first", eur("10"))
        val second = expense("second", eur("20"))
        val garden = Project("garden", "Garden", Currency.RSD, sourceCategoryIds = setOf(materials))
        projects.save(garden)
        projects.record(listOf(link("g1", first, garden)))
        projects.archive("garden", true)
        refused("Expense already linked") { projects.record(listOf(link("k2", second), link("k1", first))) }
        assertTrue(projects.readBook().costsFor("kitchen").isEmpty())
        refused("Expense selected more than once") { projects.record(listOf(link("k2", second), link("k3", second))) }
        projects.record(listOf(link("k2", second)))
        assertEquals(listOf("k2"), projects.readBook().costsFor("kitchen").map { it.id })
        assertEquals(listOf("g1"), projects.readBook().costsFor("garden").map { it.id })
    }

    @Test fun `narrowing eligible categories keeps existing links editable`() = runTest {
        seed()
        val first = expense("first", eur("10"))
        val second = expense("second", eur("20"))
        val other = expense("other", eur("5"), category = "cat-fun")
        refused("Expense category is not enabled") { projects.record(listOf(link("o", other))) }
        val linked = link("k1", first)
        projects.record(listOf(linked))
        val narrowed = kitchen.copy(sourceCategoryIds = emptySet())
        projects.save(narrowed, kitchen)
        projects.record(listOf(linked.copy(note = "Tiles")), listOf(linked))
        assertEquals("Tiles", projects.readBook().costsFor("kitchen").single().note)
        refused("Expense category is not enabled") { projects.record(listOf(link("k2", second))) }
        repo.archiveCategory(materials)
        projects.save(narrowed.copy(name = "Kitchen 2"), narrowed)
        refused("Projects link active expense categories only") { projects.save(narrowed.copy(sourceCategoryIds = setOf(materials)), narrowed.copy(name = "Kitchen 2")) }
        refused("Projects link active expense categories only") { projects.save(Project("p", "P", sourceCategoryIds = setOf("cat-income-salary"))) }
    }

    @Test fun `removed costs stay tombstoned through a rebuild while the expense can be linked again`() = runTest {
        seed()
        val tiles = expense("tiles", eur("10"))
        projects.record(listOf(link("c1", tiles)))
        projects.removeCost("c1")
        refused("Cost was removed") { projects.record(listOf(link("c1", tiles))) }
        projects.record(listOf(link("c2", tiles)))
        val before = projects.readBook()
        store.rebuild()
        assertEquals(before, projects.readBook())
        assertEquals(listOf("c2"), before.costsFor("kitchen").map { it.id })
        refused("Cost was removed") { projects.record(listOf(link("c1", tiles).copy(note = "again"))) }
    }

    @Test fun `archived projects refuse edits until restored`() = runTest {
        seed()
        val manualCost = manual("m", rsd("1000"))
        projects.record(listOf(manualCost))
        projects.archive("kitchen", true)
        val archived = kitchen.copy(archived = true)
        refused("Restore project before editing") { projects.record(listOf(manual("n", rsd("1")))) }
        refused("Restore project before editing") { projects.removeCost("m") }
        refused("Restore project before editing") { projects.saveCategory(ProjectCategory("k", "kitchen", "Tiles", "#112233")) }
        refused("Restore project before editing") { projects.save(archived.copy(name = "Renamed"), archived) }
        refused("Restore project before editing") { projects.changeCurrency(archived.copy(currency = Currency.RSD, budget = null), listOf(manualCost.into(Currency.RSD))) }
        assertTrue(projects.readBook().projects.single().archived)
        projects.archive("kitchen", false)
        projects.removeCost("m")
        assertTrue(projects.readBook().costsFor("kitchen").isEmpty())
    }

    @Test fun `an edited expense is flagged and a deleted one is left out`() = runTest {
        seed()
        val first = expense("first", eur("10"))
        val second = expense("second", eur("20"))
        val linked = link("c1", first)
        projects.record(listOf(linked, link("c2", second)))
        val edited = first.copy(money = eur("12"))
        repo.edit(edited, first)
        repo.delete("second", second)
        val book = projects.readBook()
        assertEquals(listOf("c1"), book.costsFor("kitchen").map { it.id })
        assertTrue(book.needsReview(book.costs.first { it.id == "c1" }))
        assertTrue(book.needsReview(book.costs.first { it.id == "c2" }))
        refused("Expense changed; select it again") { projects.record(listOf(linked.copy(note = "x")), listOf(linked)) }
        projects.record(listOf(link("c1", edited)), listOf(linked))
        assertFalse(projects.readBook().let { it.needsReview(it.costs.first { cost -> cost.id == "c1" }) })
    }

    @Test fun `currency changes only through one conversion of every cost from its original amount`() = runTest {
        seed()
        val costs = listOf(manual("tiny", rsd("0.50")), manual("dinar", rsd("1000")), link("euro", expense("euro", eur("12.34"))))
        projects.record(costs)
        assertEquals(listOf(eur("0.00"), eur("8.53"), eur("12.34")), costs.map { it.converted })
        val inDinar = kitchen.copy(currency = Currency.RSD, budget = rsd("585850"))
        refused("Preview currency changes first") { projects.save(inDinar, kitchen) }
        refused("Project costs changed; preview again") { projects.changeCurrency(inDinar, costs.drop(1).map { it.into(Currency.RSD) }, kitchen) }
        refused("Project changed; preview again") { projects.changeCurrency(inDinar, costs.map { it.into(Currency.RSD) }, kitchen.copy(name = "Old")) }
        refused("Project cost changed; preview again") {
            projects.changeCurrency(inDinar, costs.map { it.into(Currency.RSD) }.map { if (it.id == "dinar") it.copy(note = "x") else it }, kitchen)
        }
        assertEquals(costs.toSet(), projects.readBook().costs.toSet())
        projects.changeCurrency(inDinar, costs.map { it.into(Currency.RSD) }, kitchen)
        val converted = projects.readBook()
        assertEquals(inDinar, converted.projects.single())
        assertEquals(setOf(rsd("0.50"), rsd("1000.00"), rsd("1445.88")), converted.costs.map { it.converted }.toSet())
        projects.changeCurrency(kitchen, converted.costs.map { it.into(Currency.EUR) }, inDinar)
        assertEquals(costs.toSet(), projects.readBook().costs.toSet())
        assertEquals(kitchen, projects.readBook().projects.single())
    }

    @Test fun `currency changes retain stale and deleted expense snapshots`() = runTest {
        seed()
        val first = expense("first", eur("10"))
        val second = expense("second", eur("20"))
        val costs = listOf(link("c1", first), link("c2", second))
        projects.record(costs)
        repo.edit(first.copy(money = eur("12")), first)
        repo.delete(second.id, second)
        projects.changeCurrency(kitchen.copy(currency = Currency.RSD, budget = null), costs.map { it.into(Currency.RSD) }, kitchen)
        val book = projects.readBook()
        assertEquals(costs.map { it.original }.toSet(), book.costs.map { it.original }.toSet())
        assertTrue(book.costs.all(book::needsReview))
        assertEquals(listOf("c1"), book.costsFor(kitchen.id).map { it.id })
    }

    @Test fun `stale writes never overwrite synced changes and identities never change`() = runTest {
        seed()
        val tiles = expense("tiles", eur("10"))
        val paint = expense("paint", eur("4"))
        val renamed = kitchen.copy(name = "Kuhinja")
        projects.save(renamed, kitchen)
        refused("Project changed; reopen its settings") { projects.save(kitchen.copy(name = "Old"), kitchen) }
        refused("Project changed; reopen its settings") { projects.save(kitchen.copy(name = "New")) }
        val category = ProjectCategory("k", "kitchen", "Tiles", "#112233")
        projects.saveCategory(category)
        refused("Category changed; reopen it") { projects.saveCategory(category.copy(name = "Floor")) }
        projects.save(Project("garden", "Garden"))
        refused("Category belongs to another project") { projects.saveCategory(category.copy(projectId = "garden"), category) }
        val linked = link("c", tiles)
        projects.record(listOf(linked))
        projects.record(listOf(linked.copy(note = "synced")), listOf(linked))
        refused("Cost changed; reopen it") { projects.record(listOf(linked.copy(note = "stale")), listOf(linked)) }
        refused("Cost changed; reopen it") { projects.record(listOf(linked.copy(note = "blind"))) }
        val current = linked.copy(note = "synced")
        refused("Cost identity cannot change") { projects.record(listOf(link("c", paint).copy(note = "synced")), listOf(current)) }
        refused("Cost changed; reopen it") { projects.removeCost("c", linked) }
        assertEquals(current, projects.readBook().costs.single())
    }

    @Test fun `categories are scoped to their project and archivable`() = runTest {
        seed()
        projects.save(Project("garden", "Garden"))
        val tiles = ProjectCategory("k", "kitchen", "Tiles", "#112233")
        val seeds = ProjectCategory("g", "garden", "Seeds", "#445566")
        projects.saveCategory(tiles)
        projects.saveCategory(seeds)
        projects.saveCategory(ProjectCategory("g2", "garden", "tiles ", "#445566"))
        refused("Category name already used in this project") { projects.saveCategory(ProjectCategory("k2", "kitchen", " TILES", "#445566")) }
        refused("Choose an active category of this project") { projects.record(listOf(manual("m", rsd("1"), category = "g"))) }
        val withTiles = manual("m", rsd("1000"), category = "k")
        projects.record(listOf(withTiles))
        projects.saveCategory(tiles.copy(archived = true), tiles)
        refused("Choose an active category of this project") { projects.record(listOf(manual("n", rsd("1"), category = "k"))) }
        projects.record(listOf(withTiles.copy(note = "kept")), listOf(withTiles))
        projects.saveCategory(ProjectCategory("k2", "kitchen", "Tiles", "#445566"))
        refused("Category name already used in this project") { projects.saveCategory(tiles, tiles.copy(archived = true)) }
        assertEquals("k", projects.readBook().costs.single().categoryId)
    }

    @Test fun `book re-emits on edits that keep every row count`() = runBlocking {
        seed()
        val tiles = expense("tiles", eur("10"))
        projects.record(listOf(link("c", tiles)))
        val seen = Channel<ProjectBook>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { projects.book.collect { seen.send(it) } }
        suspend fun awaitBook(predicate: (ProjectBook) -> Boolean) = withTimeout(5_000) { while (!predicate(seen.receive())) {} }
        awaitBook { it.projects.single().name == "Kitchen" }
        projects.save(kitchen.copy(name = "Kuhinja"), kitchen)
        awaitBook { it.projects.single().name == "Kuhinja" }
        repo.edit(tiles.copy(money = eur("11")), tiles)
        awaitBook { it.needsReview(it.costs.single()) }
        job.cancel()
    }

    @Test fun `schema fifteen migration keeps history and starts with no projects`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        javaClass.getResource("/schema-v5.sql")!!.readText().split(';').filter { it.isNotBlank() }.forEach { driver.execute(null, it, 0) }
        KinjarDb.Schema.migrate(driver, 5, 15)
        val tx = Transaction("t", TransactionType.EXPENSE, rsd("1171.70"), "a", null, "c", day, "", "", "m", TransactionSource.MANUAL)
        val events = listOf(AccountCreated(Account("a", "Cash", Currency.RSD)), CategoryCreated(Category("c", "Food", "#3D4BD6")), TransactionAdded(tx))
        seedLegacyEventRows(KinjarDb(driver), events, clock.now())
        KinjarDb.Schema.migrate(driver, 15, KinjarDb.Schema.version)
        val migrated = KinjarDb(driver)
        val migratedStore = EventStore(migrated, "new", clock, Projector(migrated))
        migratedStore.rebuild()
        assertEquals(events.size.toLong(), migrated.eventQueries.count().executeAsOne())
        assertEquals(listOf(tx), BudgetRepository(migrated, migratedStore, clock).transactions.first())
        val migratedProjects = ProjectRepository(migrated, migratedStore, clock)
        assertTrue(migratedProjects.readBook().projects.isEmpty())
        val food = Project("food", "Food", Currency.EUR, sourceCategoryIds = setOf("c"))
        migratedProjects.save(food)
        migratedProjects.record(listOf(link("c", tx, food)))
        assertEquals(listOf(eur("10.00")), migratedProjects.readBook().costsFor("food").map { it.converted })
    }
}
