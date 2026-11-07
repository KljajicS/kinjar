package app.kinjar.ui.conflict

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.RecentTx
import app.kinjar.data.TransactionConflicts
import app.kinjar.data.TransactionReview
import app.kinjar.ui.WriteOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** [unsaved] rows are held transactions that do not exist on this phone yet. */
data class HeldRows(val ids: Set<String> = emptySet(), val unsaved: List<RecentTx> = emptyList()) {
    /** Stable date order keeps held rows ahead of stored ones from the same day. */
    fun mergeInto(recent: List<RecentTx>): List<RecentTx> = if (unsaved.isEmpty()) recent else
        (unsaved.filter { held -> recent.none { it.transaction.id == held.transaction.id } } + recent).sortedByDescending { it.transaction.date }
}

data class ReviewState(
    val loaded: Boolean = false,
    val review: TransactionReview? = null,
    val accountNames: Map<String, String> = emptyMap(),
    val categoryNames: Map<String, String> = emptyMap(),
)

@HiltViewModel
class TransactionConflictViewModel @Inject constructor(
    private val conflicts: TransactionConflicts,
    repo: BudgetRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    private val id: String? = saved["id"]
    val writes = WriteOperation(viewModelScope)

    val held: StateFlow<HeldRows> = combine(conflicts.held, repo.allCategories, repo.members) { held, categories, members ->
        HeldRows(held.ids, held.unsaved.map { t ->
            RecentTx(t, categories.firstOrNull { it.id == t.categoryId }?.name, members.firstOrNull { it.id == t.paidBy }?.displayName)
        })
    }.catch { emit(HeldRows()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeldRows())

    /** Not loaded until known, so an edit form never opens over a pending decision. */
    val review: StateFlow<ReviewState> = (if (id == null) flowOf(ReviewState(loaded = true)) else
        combine(conflicts.review(id), repo.allAccounts, repo.allCategories) { review, accounts, categories ->
            ReviewState(true, review, accounts.associate { it.id to it.name }, categories.associate { it.id to it.name })
        }).catch { emit(ReviewState(loaded = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState(loaded = id == null))

    fun keep(operationId: String) = writes.run { conflicts.keep(operationId) }
    fun keepCurrent() { id?.let { writes.run { conflicts.keepCurrent(it) } } }
}
