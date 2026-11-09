package app.kinjar.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import app.kinjar.domain.Account
import app.kinjar.domain.AccountKind
import app.kinjar.domain.Category
import app.kinjar.domain.Currency
import java.time.Instant
import java.time.ZoneId

class ManagementTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val repo = BudgetRepository(db, EventStore(db, "dev-1", clock, Projector(db)), clock, "dev-1")

    @Test
    fun `categories can be added, renamed and archived`() = runTest {
        repo.setUp("S", emptyList())
        repo.addCategory("Pets", "#C0392B")
        val pets = repo.categories.first().first { it.name == "Pets" }
        repo.updateCategory(Category(pets.id, "Dog", "#16A085"))
        assertEquals("Dog", repo.categories.first().first { it.id == pets.id }.name)
        repo.archiveCategory("cat-fun")
        assertNull(repo.categories.first().firstOrNull { it.id == "cat-fun" })
        assertEquals(StarterCategories.all.size + StarterCategories.income.size, repo.categories.first().size)
    }

    @Test
    fun `accounts can be added, edited and archived, member renamed`() = runTest {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)))
        repo.addAccount(Account("eur", "EUR", Currency.EUR, AccountKind.SAVINGS))
        repo.updateAccount(Account("eur", "Euro savings", Currency.EUR, AccountKind.SAVINGS))
        assertEquals("Euro savings", repo.accounts.first().first { it.id == "eur" }.name)
        repo.archiveAccount("cash")
        assertEquals(listOf("eur"), repo.accounts.first().map { it.id })
        assertEquals(listOf("cash", "eur"), repo.allAccounts.first().map { it.id }.sorted())
        repo.archiveCategory("cat-fun")
        assertEquals(StarterCategories.all.size + StarterCategories.income.size, repo.allCategories.first().size)
        val m = repo.currentMember()!!
        repo.renameMember(m.id, "Siniša")
        assertEquals("Siniša", repo.currentMember()!!.displayName)
    }
}
