package app.kinjar.data

import app.cash.sqldelight.coroutines.asFlow
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.Currency
import app.kinjar.domain.HeldChanges
import app.kinjar.domain.Money
import app.kinjar.domain.OperationCodec
import app.kinjar.domain.SyncOperation
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** [author] is the member who made the change, null when this phone cannot name them. */
data class TransactionVersion(
    val operationId: String,
    val author: String?,
    val thisPhone: Boolean,
    val madeAt: Instant,
    val transaction: Transaction?,
    val keepable: Boolean,
)

/** [current] is the value every phone shows until a version is chosen. */
data class TransactionReview(val transactionId: String, val current: Transaction?, val versions: List<TransactionVersion>)

/** [unsaved] are held transactions that do not exist yet, shown with their earliest held value. */
data class HeldTransactions(val ids: Set<String> = emptySet(), val unsaved: List<Transaction> = emptyList())

class TransactionConflicts @Inject constructor(private val db: KinjarDb, private val store: EventStore) {
    private val changes: Flow<Unit> = db.syncConflictQueries.all().asFlow().map { }

    val held: Flow<HeldTransactions> = changes.map {
        store.read {
            val pending = heldChanges() ?: return@read HeldTransactions()
            val stored = db.txQueries.all().executeAsList().mapTo(mutableSetOf()) { it.id }
            HeldTransactions(pending.transactionIds, (pending.transactionIds - stored).mapNotNull { id ->
                pending.versions(id, null).firstNotNullOfOrNull { it.transaction }
            })
        }
    }.flowOn(Dispatchers.IO)

    fun review(transactionId: String): Flow<TransactionReview?> = changes.map {
        store.read {
            val pending = heldChanges()?.takeIf { transactionId in it.transactionIds } ?: return@read null
            val current = transaction(transactionId)
            val session = db.syncSessionQueries.current().executeAsOneOrNull()
            val members = db.memberQueries.all().executeAsList()
            fun author(op: SyncOperation) = (members.firstOrNull { op.authorUid == session?.uid && it.id == session?.member_id }
                ?: members.firstOrNull { it.device_id == op.authorUid } ?: members.firstOrNull { it.device_id == op.installId })?.display_name
            TransactionReview(transactionId, current, pending.versions(transactionId, current).map { v ->
                TransactionVersion(v.operation.id, author(v.operation), v.operation.installId == session?.install_id,
                    Instant.ofEpochMilli(v.operation.events.first().occurredAt), v.transaction, v.keepable)
            })
        }
    }.flowOn(Dispatchers.IO)

    suspend fun keep(operationId: String) = withContext(Dispatchers.IO) { store.keepConflictingChange(operationId) }

    /** Rejects every held version of the transaction, leaving the value shown before them. */
    suspend fun keepCurrent(transactionId: String) = withContext(Dispatchers.IO) {
        store.discardConflictingChanges(store.read { heldChanges()?.roots(transactionId).orEmpty() })
    }

    private fun heldChanges(): HeldChanges? {
        val reasons = db.syncConflictQueries.all().executeAsList().associate { it.operation_id to it.reason }
        if (reasons.isEmpty()) return null
        return HeldChanges(db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload) }, reasons)
    }

    private fun transaction(id: String): Transaction? = db.txQueries.all().executeAsList().firstOrNull { it.id == id }?.run {
        Transaction(id, TransactionType.valueOf(type), Money.of(amount, Currency.valueOf(currency)), account_id, to_account_id,
            category_id, LocalDate.parse(date), note, merchant, paid_by, TransactionSource.valueOf(source))
    }
}
