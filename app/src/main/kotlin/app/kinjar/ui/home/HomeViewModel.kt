package app.kinjar.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.RecentTx
import app.kinjar.domain.Account
import app.kinjar.domain.HomeSummary
import app.kinjar.domain.Money
import app.kinjar.ui.RateStatus
import java.time.LocalDate
import javax.inject.Inject

data class AccountBalance(val account: Account, val balance: Money)

data class HomeState(
    val summary: HomeSummary,
    val accounts: List<AccountBalance>,
    val recent: List<RecentTx>,
    val accountNames: Map<String, String>,
    val currentMemberId: String?,
    val today: LocalDate,
    val memberName: String = "",
    val rateStatus: RateStatus = RateStatus(),
    val hasOlder: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(repo: BudgetRepository, clock: Clock, private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val limit = if (saved.get<Boolean>("activity") == true) Int.MAX_VALUE - 1 else 5
    private val attempt = MutableStateFlow(0)
    private val failure = MutableStateFlow(false)
    /** A failed read keeps the last good screen and offers [retry] instead of crashing the app. */
    val failed: StateFlow<Boolean> = failure

    // One extra row tells whether older transactions exist without a separate count query.
    private val recent = repo.recent(limit + 1).map { rows -> rows.take(limit) to (rows.size > limit) }

    val state: StateFlow<HomeState?> = attempt.flatMapLatest {
        combine(
            repo.financial,
            recent,
            flow { emit(repo.currentMember()?.id) },
            repo.members,
        ) { financial, (rows, hasOlder), member, members ->
            HomeState(
                financial.home,
                financial.activeAccounts.mapNotNull { a -> financial.home.balances[a.id]?.let { AccountBalance(a, it) } },
                rows,
                financial.accounts.associate { it.id to it.name },
                member,
                financial.day,
                memberName = members.firstOrNull { it.id == member }?.displayName ?: "",
                rateStatus = RateStatus.from(financial),
                hasOlder = hasOlder,
            )
        }
            .onEach { failure.value = false }
            .catch { failure.value = true }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun retry() = attempt.update { it + 1 }
}
