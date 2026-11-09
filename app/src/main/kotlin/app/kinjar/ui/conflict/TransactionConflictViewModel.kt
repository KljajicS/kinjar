package app.kinjar.ui.conflict

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.DiagnosticOperation
import app.kinjar.data.Diagnostics
import app.kinjar.data.RecentTx
import app.kinjar.data.TransactionConflicts
import app.kinjar.data.TransactionReview
import app.kinjar.ui.WriteOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** [unsaved] rows are held transactions that do not exist on this phone yet. [failed] keeps the last rows read. */
data class HeldRows(val ids: Set<String> = emptySet(), val unsaved: List<RecentTx> = emptyList(), val failed: Boolean = false) {
    /** Stable date order keeps held rows ahead of stored ones from the same day. */
    fun mergeInto(recent: List<RecentTx>): List<RecentTx> = if (unsaved.isEmpty()) recent else
        (unsaved.filter { held -> recent.none { it.transaction.id == held.transaction.id } } + recent).sortedByDescending { it.transaction.date }
}

/** [failed] keeps the last review read; [loaded] stays false until one read succeeds. */
data class ReviewState(
    val loaded: Boolean = false,
    val review: TransactionReview? = null,
    val accountNames: Map<String, String> = emptyMap(),
    val categoryNames: Map<String, String> = emptyMap(),
    val failed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionConflictViewModel @Inject constructor(
    private val conflicts: TransactionConflicts,
    repo: BudgetRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    private val id: String? = saved["id"]
    private val attempts = MutableStateFlow(0)
    val writes = WriteOperation(viewModelScope)

    val held: StateFlow<HeldRows> = attempts.flatMapLatest {
        combine(conflicts.held, repo.allCategories, repo.members) { held, categories, members ->
            HeldRows(held.ids, held.unsaved.map { t ->
                val category = categories.firstOrNull { it.id == t.categoryId }
                RecentTx(t, category?.name, members.firstOrNull { it.id == t.paidBy }?.displayName, category?.icon)
            })
        }.catch { Diagnostics.report(DiagnosticOperation.CONFLICT_READ, it); emit(held.value.copy(failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeldRows())

    /** Not loaded until known, so an edit form never opens over a pending decision; a failed read never reads as resolved. */
    val review: StateFlow<ReviewState> = (if (id == null) flowOf(ReviewState(loaded = true)) else attempts.flatMapLatest {
        combine(conflicts.review(id), repo.allAccounts, repo.allCategories) { review, accounts, categories ->
            ReviewState(true, review, accounts.associate { it.id to it.name }, categories.associate { it.id to it.name })
        }.catch { Diagnostics.report(DiagnosticOperation.CONFLICT_READ, it); emit(review.value.copy(failed = true)) }
    })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState(loaded = id == null))

    fun retry() { attempts.value++ }

    fun keep(operationId: String) {
        if (!review.value.failed && review.value.review != null) writes.run { conflicts.keep(operationId) }
    }
    fun keepCurrent() {
        if (!review.value.failed && review.value.review != null) id?.let { writes.run { conflicts.keepCurrent(it) } }
    }
}
