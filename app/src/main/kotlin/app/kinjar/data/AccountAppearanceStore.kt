package app.kinjar.data

import android.content.Context
import app.kinjar.domain.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class AccountSymbol { WALLET, CARD, BANK, SAVINGS, BUSINESS, TRAVEL, HOME }

@Serializable
enum class AccountTint { PURPLE, GREEN, BLUE, ORANGE, PINK, GRAY, RED, TEAL, CYAN, YELLOW, LIME, INDIGO }

@Serializable
data class AccountAppearance(
    val icon: AccountSymbol = AccountSymbol.BANK,
    val color: AccountTint = AccountTint.PURPLE,
    val favorite: Boolean = false,
    val showInAdd: Boolean = true,
)

@Serializable
data class AccountAppearances(
    val initialized: Boolean = false,
    val accounts: Map<String, AccountAppearance> = emptyMap(),
) {
    fun appearance(id: String) = accounts[id] ?: AccountAppearance()

    fun visibleInAdd(active: List<Account>): List<Account> = active.filter { !it.archived && appearance(it.id).showInAdd }

    fun favorites(active: List<Account>): List<Account> = visibleInAdd(active).let { eligible ->
        if (initialized) eligible.filter { appearance(it.id).favorite }.take(3) else eligible.take(3)
    }

    fun withDefaults(active: List<Account>): AccountAppearances = if (initialized) this else copy(
        initialized = true,
        accounts = accounts + favorites(active).associate { it.id to appearance(it.id).copy(favorite = true) },
    )
}

class AccountAppearanceStore(
    read: () -> String? = { null },
    private val write: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(read()?.let { runCatching { json.decodeFromString<AccountAppearances>(it) }.getOrNull() } ?: AccountAppearances())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun save(id: String, appearance: AccountAppearance, active: List<Account>) {
        val current = state.value.withDefaults(active)
        val others = current.visibleInAdd(active).count { it.id != id && current.appearance(it.id).favorite }
        val normalized = if (appearance.showInAdd) appearance else appearance.copy(favorite = false)
        require(!normalized.favorite || others < 3) { "At most three favorite accounts" }
        val updated = current.copy(accounts = current.accounts + (id to normalized))
        write(json.encodeToString(updated))
        mutableState.value = updated
    }

    companion object {
        fun fromPreferences(context: Context): AccountAppearanceStore {
            val prefs = context.getSharedPreferences("account_appearance", Context.MODE_PRIVATE)
            return AccountAppearanceStore({ prefs.getString("accounts", null) }) {
                check(prefs.edit().putString("accounts", it).commit()) { "Could not save account preferences" }
            }
        }
    }
}
