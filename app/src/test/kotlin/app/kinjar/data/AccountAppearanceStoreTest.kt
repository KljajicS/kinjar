package app.kinjar.data

import app.kinjar.domain.Account
import app.kinjar.domain.Currency
import org.junit.Assert.*
import org.junit.Test

class AccountAppearanceStoreTest {
    private val accounts = (1..10).map { Account("$it", "Account $it", Currency.RSD) }

    @Test fun `add visibility persists locally without hiding accounts on another phone`() {
        var saved: String? = null
        val mine = AccountAppearanceStore({ saved }) { saved = it }
        mine.save("1", AccountAppearance(favorite = true, showInAdd = false), accounts)
        val restored = AccountAppearanceStore({ saved })
        assertEquals(9, restored.state.value.visibleInAdd(accounts).size)
        assertFalse(restored.state.value.appearance("1").favorite)
        assertEquals(10, AccountAppearanceStore().state.value.visibleInAdd(accounts).size)
        assertTrue(AccountAppearanceStore({ "{\"initialized\":true,\"accounts\":{\"1\":{\"favorite\":true}}}" }).state.value.appearance("1").showInAdd)
    }

    @Test fun `defaults follow account order and persisted choices survive restart`() {
        var saved: String? = null
        val store = AccountAppearanceStore({ saved }) { saved = it }
        assertEquals(listOf("1", "2", "3"), store.state.value.favorites(accounts).map { it.id })
        store.save("2", AccountAppearance(AccountSymbol.CARD, AccountTint.BLUE, false), accounts)
        store.save("8", AccountAppearance(AccountSymbol.TRAVEL, AccountTint.ORANGE, true), accounts)
        val restored = AccountAppearanceStore({ saved })
        assertEquals(listOf("1", "3", "8"), restored.state.value.favorites(accounts).map { it.id })
        assertEquals(AccountSymbol.CARD, restored.state.value.appearance("2").icon)
        assertEquals(AccountTint.BLUE, restored.state.value.appearance("2").color)
        assertEquals(listOf("8", "3", "1"), restored.state.value.favorites(accounts.reversed()).map { it.id })
    }

    @Test fun `fourth favorite is rejected and archived favorites do not consume a slot`() {
        val store = AccountAppearanceStore()
        assertTrue(runCatching { store.save("4", AccountAppearance(favorite = true), accounts) }.isFailure)
        val active = accounts.map { if (it.id == "2") it.copy(archived = true) else it }
        store.save("1", AccountAppearance(favorite = true), accounts)
        store.save("4", AccountAppearance(favorite = true), active)
        assertEquals(listOf("1", "3", "4"), store.state.value.favorites(active).map { it.id })
    }

    @Test fun `failed storage does not publish changes and corrupt storage falls back safely`() {
        val store = AccountAppearanceStore({ "broken" }) { error("Disk full") }
        val before = store.state.value
        assertTrue(runCatching { store.save("1", AccountAppearance(icon = AccountSymbol.HOME), accounts) }.isFailure)
        assertEquals(before, store.state.value)
        assertEquals(3, store.state.value.favorites(accounts).size)
    }
}
