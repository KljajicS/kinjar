package app.kinjar.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ProjectSyncIntegrityTest {
    private val date = LocalDate.of(2026, 11, 10)
    private fun eur(amount: String) = Money.of(amount, Currency.EUR)
    private val project = Project("house", "House")
    private val tx = Transaction("expense", TransactionType.EXPENSE, eur("100"), "bank", null, null, date, "", "", "member", TransactionSource.MANUAL)
    private val cost = ProjectCost("cost", project.id, null, tx.money, tx.money, date, "", tx.id)
    private val baseline = listOf(AccountCreated(Account("bank", "Bank", Currency.EUR)), TransactionAdded(tx), ProjectSaved(project))

    @Test fun `project events announce version five and round trip`() {
        val operation = SyncOperation("family", "dataset", "user", "device", 1, emptySet(), mapOf("project:house" to ResourceAccess.WRITE),
            listOf(OperationEvent("event", "device", 1, 1, ProjectSaved(project))))
        assertEquals(5, operation.version)
        assertEquals(operation, OperationCodec.decode(OperationCodec.encode(operation)))
        val future = String(OperationCodec.encode(operation)).replace("\"version\":5", "\"version\":6")
        assertThrows(UnsupportedSyncVersion::class.java) { OperationCodec.decode(future.toByteArray()) }
    }

    @Test fun `partial currency change rolls back before a complete atomic change`() {
        val integrity = FinancialIntegrity().applying(baseline + ProjectCostSaved(cost), true)
        val target = project.copy(currency = Currency.RSD)
        assertThrows(IllegalArgumentException::class.java) { integrity.applyAtomically(listOf(ProjectSaved(target)), true) }
        val converted = cost.copy(converted = Money.of("11750", Currency.RSD), ratesToRsd = mapOf(Currency.EUR to "117.5"), rateSource = "manual")
        integrity.applyAtomically(listOf(ProjectSaved(target), ProjectCostSaved(converted)), true)
        integrity.validate()
    }

    @Test fun `duplicate project link and forged removal are rejected without losing prior link`() {
        val other = project.copy(id = "garden", name = "Garden")
        val integrity = FinancialIntegrity().applying(baseline + ProjectSaved(other) + ProjectCostSaved(cost), true)
        assertThrows(IllegalArgumentException::class.java) {
            integrity.applyAtomically(listOf(ProjectCostSaved(cost.copy(id = "duplicate", projectId = other.id))), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            integrity.applyAtomically(listOf(ProjectCostRemoved(cost.id, project.id)), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            integrity.applyAtomically(listOf(
                ProjectCostSaved(cost.copy(id = "replacement", projectId = other.id)),
                ProjectCostRemoved(cost.id, project.id, tx.id),
            ), true)
        }
        integrity.applyAtomically(listOf(ProjectCostRemoved(cost.id, project.id, tx.id)), true)
        integrity.applyAtomically(listOf(ProjectCostSaved(cost.copy(id = "replacement", projectId = other.id))), true)
        integrity.validate()
    }

    @Test fun `changed transaction keeps history but cannot be silently reconfirmed with old amounts`() {
        val integrity = FinancialIntegrity().applying(baseline + ProjectCostSaved(cost), true)
        integrity.applyAtomically(listOf(TransactionEdited(tx.copy(money = eur("150")))), true)
        assertThrows(IllegalArgumentException::class.java) {
            integrity.applyAtomically(listOf(ProjectCostSaved(cost.copy(original = eur("120"), converted = eur("120")))), true)
        }
        integrity.applyAtomically(listOf(ProjectCostSaved(cost.copy(original = eur("150"), converted = eur("150")))), true)
        integrity.validate()
    }

    @Test fun `concurrent links across different projects collide on the expense`() {
        fun link(device: String, projectId: String) = SyncOperation("family", "dataset", "user", device, 1, emptySet(),
            mapOf("project:$projectId" to ResourceAccess.WRITE, "transaction:${tx.id}" to ResourceAccess.WRITE),
            listOf(OperationEvent(device, device, 1, 1, ProjectCostSaved(cost.copy(id = device, projectId = projectId)))))
        val result = ConflictResolver.replay(listOf(link("one", "house"), link("two", "garden")))
        assertEquals(setOf("one:1", "two:1"), result.conflicts)
        assertTrue(result.applicable.isEmpty())
    }
}
