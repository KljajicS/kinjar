package app.kinjar.ui.setup

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import app.kinjar.R
import app.kinjar.data.BudgetRepository
import app.kinjar.domain.Account
import app.kinjar.domain.Currency
import app.kinjar.domain.newId
import app.kinjar.ui.WriteOperation
import javax.inject.Inject

/** Starter accounts carry a [label] so the saved name follows the app language; [name] is only their fallback. */
data class SetupAccount(val name: String, val currency: Currency, val enabled: Boolean, @StringRes val label: Int? = null)

data class SetupState(
    val name: String = "",
    val accounts: List<SetupAccount> = listOf(
        SetupAccount("Cash", Currency.RSD, enabled = true, label = R.string.setup_account_cash),
        SetupAccount("Card", Currency.RSD, enabled = true, label = R.string.setup_account_card),
        SetupAccount("Savings", Currency.RSD, enabled = false, label = R.string.audit_ui_account_savings),
    ),
    val done: Boolean = false,
) {
    val canFinish: Boolean get() = name.isNotBlank() && accounts.any { it.enabled }
    fun selectedAccounts(localize: (Int) -> String? = { null }): List<Account> = accounts.filter { it.enabled }.map { a ->
        Account(newId(), a.label?.let(localize) ?: a.name, a.currency)
    }
}

@HiltViewModel
class SetupViewModel @Inject constructor(private val repo: BudgetRepository) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun toggleAccount(index: Int) = _state.update { s ->
        s.copy(accounts = s.accounts.mapIndexed { i, a -> if (i == index) a.copy(enabled = !a.enabled) else a })
    }

    fun addAccount(name: String, currency: Currency) = _state.update { s ->
        if (name.isBlank()) s else s.copy(accounts = s.accounts + SetupAccount(name.trim(), currency, enabled = true))
    }

    fun finish(localize: (Int) -> String? = { null }) {
        val s = _state.value
        if (!s.canFinish) return
        writes.run {
            repo.setUp(
                s.name.trim(),
                s.selectedAccounts(localize),
            )
            _state.update { it.copy(done = true) }
        }
    }
}
