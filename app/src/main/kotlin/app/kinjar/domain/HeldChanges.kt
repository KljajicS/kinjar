package app.kinjar.domain

/** What [transactionId] becomes if [operation] is kept; null when it would not exist. */
data class ConflictVersion(val operation: SyncOperation, val transaction: Transaction?, val keepable: Boolean)

/**
 * Sync changes held by replay, read per transaction. Choices are whole operations, so events saved together
 * (a loan payment and its expense) are kept or rejected together. Keeping one rejects only the held
 * operations that compete with it directly, leaving unrelated held changes for their own decision.
 */
class HeldChanges(operations: Collection<SyncOperation>, private val reasons: Map<String, String>) {
    private val held = operations.filter { it.id in reasons }.associateBy { it.id }
    private val choices = held.values.filter { reasons[it.id] != WAITING }

    val transactionIds: Set<String> = held.values.flatMapTo(mutableSetOf()) { it.writtenTransactions() }

    fun competitors(id: String): Set<String> {
        val operation = held[id] ?: return emptySet()
        return choices.filter { it.id != id && ConflictResolver.collides(operation, it) }.mapTo(mutableSetOf()) { it.id }
    }

    fun keepable(id: String): Boolean =
        reasons[id] == CONCURRENT && held[id]?.events?.isNotEmpty() == true && competitors(id).isNotEmpty()

    /** The choices deciding [transactionId]: held changes to it, or the choices a waiting change to it descends from. */
    fun roots(transactionId: String): Set<String> = held.values.filter { transactionId in it.writtenTransactions() }
        .flatMapTo(mutableSetOf()) { op ->
            if (reasons[op.id] != WAITING) setOf(op.id) else heldAncestors(op.id).filterTo(mutableSetOf()) { reasons[it] != WAITING }
        }

    fun versions(transactionId: String, current: Transaction?): List<ConflictVersion> {
        val roots = roots(transactionId)
        return (roots + roots.flatMap(::competitors)).map(held::getValue).filter { it.events.isNotEmpty() }
            .sortedWith(compareBy({ it.events.first().occurredAt }, { it.id }))
            .map { op -> ConflictVersion(op, lineage(op.id).fold(current) { tx, next -> tx.after(next, transactionId) }, keepable(op.id)) }
    }

    private fun heldAncestors(id: String): Set<String> {
        val found = mutableSetOf<String>()
        val pending = ArrayDeque(held[id]?.parents.orEmpty())
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next in held && found.add(next)) pending += held.getValue(next).parents
        }
        return found
    }

    /** [id] and the held changes that would follow it once kept, in replay order. */
    private fun lineage(id: String): List<SyncOperation> {
        val excluded = competitors(id)
        val members = held.values.filter { op ->
            op.id == id || heldAncestors(op.id).let { id in it && it.none(excluded::contains) }
        }.associateBy { it.id }
        val ordered = mutableListOf<SyncOperation>()
        val remaining = members.toMutableMap()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { op -> op.parents.none { it in remaining } }.sortedBy { it.id }
            if (ready.isEmpty()) break
            ordered += ready
            ready.forEach { remaining.remove(it.id) }
        }
        return ordered
    }

    private companion object {
        const val WAITING = "WAITING_FOR_DEPENDENCY"
        const val CONCURRENT = "CONCURRENT_CHANGE"
    }
}

private fun SyncOperation.writtenTransactions(): List<String> =
    if (events.isNotEmpty() && events.all { it.event is BillSkipped }) emptyList() else
        resources.filter { (key, mode) -> mode == ResourceAccess.WRITE && key.startsWith("transaction:") }.keys.map { it.removePrefix("transaction:") }

/** Mirrors the projector: adding an existing transaction and editing a missing one change nothing. */
private fun Transaction?.after(operation: SyncOperation, id: String): Transaction? = operation.events.fold(this) { tx, record ->
    when (val e = record.event) {
        is TransactionAdded -> if (e.transaction.id == id) tx ?: e.transaction else tx
        is TransactionEdited -> if (e.transaction.id == id && tx != null) e.transaction else tx
        is TransactionDeleted -> if (e.transactionId == id) null else tx
        else -> tx
    }
}
