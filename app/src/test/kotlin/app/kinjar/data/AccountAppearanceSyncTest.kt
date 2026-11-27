package app.kinjar.data

import app.kinjar.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AccountAppearanceSyncTest {
    private val clock = FixedClock(Instant.parse("2026-11-24T10:00:00Z"), ZoneId.of("UTC"))
    private val db = testDb()
    private val store = EventStore(db, "phone", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "phone")

    @Test fun `matching offline appearance migrations allow expenses transfers and bank reviews after sync`() = runTest {
        repo.setUp("Tester", listOf(Account("kdev", "Kdev RSD", Currency.RSD), Account("stek", "Stek", Currency.RSD)))
        val member = requireNotNull(repo.currentMember()).id
        val baseline = store.snapshot()
        val peerDb = testDb()
        val peer = EventStore(peerDb, "peer", clock, Projector(peerDb))
        peer.activate("user", "family", "dataset", member, 1, baseline, peer.currentDigest(), false)
        store.activate("user", "family", "dataset", member, 1, baseline, store.currentDigest(), false)
        val local = AccountAppearances(accounts = mapOf("kdev" to AccountAppearance()))
        repo.migrateAccountAppearances(local)
        BudgetRepository(peerDb, peer, clock, "peer").migrateAccountAppearances(local)
        store.receive(peerDb.syncOperationQueries.all().executeAsList().map { it.payload }, "user", "family", "dataset", 0, 0, "")
        store.rebuild()

        val expense = Transaction("control", TransactionType.EXPENSE, Money.of("12", Currency.RSD), "stek",
            null, "cat-groceries", clock.today(), "", "", member, TransactionSource.MANUAL)
        repo.add(expense)
        val affected = expense.copy(id = "expense", accountId = "kdev")
        repo.add(affected)
        repo.add(affected.copy(id = "transfer", type = TransactionType.TRANSFER, categoryId = null, toAccountId = "stek"))
        db.bankInboxQueries.capture("alert", "YETTEL", "Fixture", "", 1)
        repo.saveBankDraft("alert", affected.copy(id = "bank"))
        store.rebuild()
        assertEquals(setOf("control", "expense", "transfer", "bank"), db.txQueries.all().executeAsList().map { it.id }.toSet())
        assertNull(db.bankInboxQueries.pendingById("alert").executeAsOneOrNull())
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
    }

    @Test fun `account and category appearance reaches another phone and survives legacy edits and replay`() = runTest {
        store.activate("user", "family", "dataset", "member", 1, store.snapshot(), store.currentDigest(), false)
        val account = Account("a", "Cash", Currency.RSD, icon = "WALLET", color = "GREEN")
        repo.addAccount(account)
        repo.addCategory("Food", "#123456", "cart")
        repo.updateAccount(account.copy(icon = "CARD", color = "BLUE"))
        val category = repo.categories.first().single()
        repo.updateCategory(category.copy(icon = "work", color = "#654321"))
        repo.updateAccount(Account("a", "Renamed by old phone", Currency.RSD))

        val peerDb = testDb()
        val peer = EventStore(peerDb, "peer", clock, Projector(peerDb))
        val peerRepo = BudgetRepository(peerDb, peer, clock, "peer")
        peer.activate("user", "family", "dataset", "member", 1, peer.snapshot(), peer.currentDigest(), false)
        val payloads = db.syncOperationQueries.all().executeAsList().map { it.payload }
        assertEquals(listOf(13, 4, 13, 4, 1), payloads.map { OperationCodec.decode(it).version })
        peer.receive(payloads, "user", "family", "dataset", 0, 0, "")
        assertEquals(account.copy(name = "Renamed by old phone", icon = "CARD", color = "BLUE"), peerRepo.accounts.first().single())
        assertEquals(category.copy(icon = "work", color = "#654321"), peerRepo.categories.first().single())
        peer.receive(payloads, "user", "family", "dataset", 0, 0, "")
        peer.rebuild()
        peer.validateHistory(peer.snapshot())
        assertEquals("BLUE", peerRepo.accounts.first().single().color)

        val local = AccountAppearances(accounts = mapOf("a" to AccountAppearance(AccountSymbol.HOME, AccountTint.RED, true, false)))
        val displayed = local.withSharedAppearance(peerRepo.accounts.first()).appearance("a")
        assertEquals(AccountAppearance(AccountSymbol.CARD, AccountTint.BLUE, true, false), displayed)
    }

    @Test fun `migration preserves choices and existing shared values and is idempotent`() = runTest {
        repo.addAccount(Account("a", "Cash", Currency.RSD))
        repo.addAccount(Account("b", "Bank", Currency.EUR, icon = "BANK", color = "BLUE"))
        val local = AccountAppearances(accounts = mapOf(
            "a" to AccountAppearance(AccountSymbol.WALLET, AccountTint.GREEN),
            "b" to AccountAppearance(AccountSymbol.HOME, AccountTint.RED),
        ))
        repo.migrateAccountAppearances(local)
        val count = db.eventQueries.count().executeAsOne()
        repo.migrateAccountAppearances(local)
        assertEquals(count, db.eventQueries.count().executeAsOne())
        val accounts = repo.accounts.first().associateBy { it.id }
        assertEquals("WALLET", accounts.getValue("a").icon)
        assertEquals("GREEN", accounts.getValue("a").color)
        assertEquals("BANK", accounts.getValue("b").icon)
        assertEquals("BLUE", accounts.getValue("b").color)
    }

    @Test fun `failed migration can retry without losing local choices`() = runTest {
        repo.addAccount(Account("a", "Cash", Currency.RSD))
        val local = AccountAppearances(accounts = mapOf("a" to AccountAppearance(AccountSymbol.CARD, AccountTint.RED)))
        db.syncSessionQueries.save("user", "family", "dataset", "member", "phone", 1, "PAUSED")
        repo.migrateAccountAppearances(local)
        assertNull(repo.accounts.first().single().icon)
        db.syncSessionQueries.save("user", "family", "dataset", "member", "phone", 1, "ACTIVE")
        repo.migrateAccountAppearances(local)
        assertEquals("CARD", repo.accounts.first().single().icon)
    }

    @Test fun `legacy account bytes stay unchanged and appearance requires new reader`() {
        val legacy = """{"type":"AccountCreated","account":{"id":"a","name":"Cash","currency":"RSD","kind":"SPENDING","archived":false}}"""
        assertEquals(legacy, EventJson.encodeToString(Event.serializer(), AccountCreated(Account("a", "Cash", Currency.RSD))))
        assertEquals(legacy, EventJson.encodeToString(Event.serializer(), EventJson.decodeFromString(Event.serializer(), legacy)))
        val operation = SyncOperation("family", "dataset", "user", "phone", 1, emptySet(), mapOf("account:a" to ResourceAccess.WRITE),
            listOf(OperationEvent("event", "phone", 1, 0, AccountCreated(Account("a", "Cash", Currency.RSD, icon = "CARD")))))
        val bytes = OperationCodec.encode(operation)
        assertEquals(operation, OperationCodec.decode(bytes))
        assertThrows(IllegalArgumentException::class.java) {
            OperationCodec.decode(String(bytes).replace("\"version\":13", "\"version\":12").toByteArray())
        }
    }
}
