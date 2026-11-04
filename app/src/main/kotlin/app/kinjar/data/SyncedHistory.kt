package app.kinjar.data

import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

internal class SyncedHistory(private val db: KinjarDb, private val projector: Projector) {
    fun snapshot(): FamilyHistory {
        val baseline = db.syncBaselineQueries.current().executeAsOneOrNull()?.let(HistoryCodec::decode)
        if (baseline == null) return FamilyHistory(baseline = db.eventQueries.allInOrder().executeAsList().map {
            OperationEvent(it.id, it.device_id, it.seq, it.occurred_at, EventJson.decodeFromString<Event>(it.payload))
        }, operations = emptyList())
        return baseline.copy(operations = db.syncOperationQueries.all().executeAsList().map { Base64.getEncoder().encodeToString(it.payload) })
    }

    fun replace(history: FamilyHistory) {
        val operationBytes = history.operations.map { Base64.getDecoder().decode(it) }
        val operations = operationBytes.map(OperationCodec::decode)
        require(operations.map { it.id }.distinct().size == operations.size)
        operations.forEach { validateResources(it, history.baseline, operations) }
        db.syncOutboxQueries.deleteAll()
        db.syncOperationQueries.deleteAll()
        db.syncConflictQueries.deleteAll()
        db.syncCheckpointQueries.deleteAll()
        db.eventQueries.deleteAll()
        db.syncBaselineQueries.save(HistoryCodec.encode(history.copy(operations = emptyList())))
        history.baseline.forEach(::insert)
        operations.zip(operationBytes).forEach { (operation, bytes) ->
            db.syncOperationQueries.insert(operation.id, operation.installId, operation.sequence, bytes)
            operation.events.forEach(::insert)
        }
        rebuild()
    }

    fun receive(payloads: List<ByteArray>, familyId: String, datasetId: String) {
        val incoming = payloads.map(OperationCodec::decode)
        val baseline = requireNotNull(db.syncBaselineQueries.current().executeAsOneOrNull()).let(HistoryCodec::decode).baseline
        val known = db.syncOperationQueries.all().executeAsList().associateBy { it.id }
        val context = known.values.map { OperationCodec.decode(it.payload) } + incoming
        incoming.zip(payloads).forEach { (operation, bytes) ->
            require(operation.familyId == familyId && operation.datasetId == datasetId)
            val existing = known[operation.id]
            if (existing != null) require(existing.payload.contentEquals(bytes)) { "Conflicting operation identity" }
            else {
                validateResources(operation, baseline, context)
                db.syncOperationQueries.insert(operation.id, operation.installId, operation.sequence, bytes)
                operation.events.forEach(::insert)
            }
        }
    }

    fun rebuild() {
        val baseline = requireNotNull(db.syncBaselineQueries.current().executeAsOneOrNull()).let(HistoryCodec::decode)
        val operations = db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload) }
        val replay = ConflictResolver.replay(operations.map { it.copy(familyId = "history", datasetId = "history") })
        projector.clear()
        var integrity = FinancialIntegrity().applying(baseline.baseline.map { it.event }, strict = false)
        integrity.validate()
        baseline.baseline.forEach { projector.apply(it.record()) }
        db.syncConflictQueries.deleteAll()
        replay.conflicts.forEach { db.syncConflictQueries.save(it, "CONCURRENT_CHANGE") }
        replay.waiting.forEach { db.syncConflictQueries.save(it, "WAITING_FOR_DEPENDENCY") }
        val invalid = mutableSetOf<String>()
        replay.applicable.forEach { operation ->
            if (operation.parents.any { it in invalid }) {
                invalid += operation.id
                db.syncConflictQueries.save(operation.id, "WAITING_FOR_DEPENDENCY")
            } else {
                val next = runCatching { integrity.applying(operation.events.map { it.event }, strict = true) }
                if (next.isFailure) {
                    invalid += operation.id
                    db.syncConflictQueries.save(operation.id, "INVALID_FINANCIAL_CHANGE")
                } else {
                    integrity = next.getOrThrow()
                    operation.events.forEach { projector.apply(it.record()) }
                }
            }
        }
    }

    private fun insert(event: OperationEvent) {
        val payload = EventJson.encodeToString(Event.serializer(), event.event)
        val prior = db.eventQueries.bySequence(event.deviceId, event.seq).executeAsOneOrNull()
        if (prior != null) {
            require(prior.id == event.id && prior.occurred_at == event.occurredAt && prior.payload == payload) { "Conflicting historical identity" }
            return
        }
        db.eventQueries.insert(event.id, event.deviceId, event.seq, event.occurredAt,
            EventJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content, payload)
    }

    private fun validateResources(operation: SyncOperation, baseline: List<OperationEvent>, known: List<SyncOperation>) {
        val events = baseline.map { it.event } + known.flatMap { it.events }.map { it.event }
        val payments = events.filterIsInstance<LoanPaymentAdded>().associate { it.payment.id to it.payment }
        fun requireAccess(key: String, write: Boolean = true) {
            require(operation.resources[key]?.let { !write || it == ResourceAccess.WRITE } == true) { "Missing operation dependency" }
        }
        fun transaction(tx: Transaction) {
            requireAccess("transaction:${tx.id}")
            requireAccess("account:${tx.accountId}", false)
            tx.toAccountId?.let { requireAccess("account:$it", false) }
            tx.categoryId?.let { requireAccess("category:$it", false) }
        }
        operation.events.forEach { record ->
            when (val e = record.event) {
                is AccountCreated -> requireAccess("account:${e.account.id}")
                is AccountUpdated -> requireAccess("account:${e.account.id}")
                is AccountArchived -> requireAccess("account:${e.accountId}")
                is TransactionAdded -> { transaction(e.transaction); e.receipt?.let { requireAccess("receipt:${it.id}") } }
                is TransactionEdited -> transaction(e.transaction)
                is TransactionDeleted -> requireAccess("transaction:${e.transactionId}")
                is LoanCreated -> requireAccess("loan:${e.loan.id}")
                is LoanUpdated -> requireAccess("loan:${e.loan.id}")
                is LoanArchived -> requireAccess("loan:${e.loanId}")
                is LoanPaymentAdded -> { requireAccess("loan:${e.payment.loanId}"); requireAccess("transaction:${e.payment.transactionId}") }
                is LoanPaymentUpdated -> { requireAccess("loan:${e.payment.loanId}"); requireAccess("transaction:${e.payment.transactionId}") }
                is LoanPaymentRemoved -> payments[e.paymentId]?.let { requireAccess("loan:${it.loanId}"); requireAccess("transaction:${it.transactionId}") }
                is LoanPrincipalAdjusted -> requireAccess("loan:${e.adjustment.loanId}")
                is LoanReceiptLinked -> { requireAccess("loan:${e.loanId}"); requireAccess("transaction:${e.transactionId}") }
                is CategoryCreated -> requireAccess("category:${e.category.id}")
                is CategoryUpdated -> requireAccess("category:${e.category.id}")
                is CategoryArchived -> requireAccess("category:${e.categoryId}")
                is MemberCreated -> requireAccess("member:${e.member.id}")
                is MemberRenamed -> requireAccess("member:${e.memberId}")
                is GoalSet -> requireAccess("goal:${e.goal.id}")
                is GoalArchived -> requireAccess("goal:${e.goalId}")
                is AccountsReordered -> requireAccess("order:accounts")
                is GoalsReordered -> requireAccess("order:goals")
                is RecurringRuleSet -> { requireAccess("rule:${e.rule.id}"); requireAccess("account:${e.rule.accountId}", false) }
                is RecurringRuleArchived -> requireAccess("rule:${e.ruleId}")
                is BillSkipped -> { requireAccess("rule:${e.ruleId}", false); requireAccess("transaction:${billTxId(e.ruleId, e.period)}") }
                is GoalAllocated -> { requireAccess("goal:${e.allocation.goalId}", false); requireAccess("allocation:${e.allocation.id}") }
                is GoalAllocationDeleted -> requireAccess("allocation:${e.allocationId}")
                is BudgetSet -> requireAccess("budget:${e.budget.categoryId}:${e.budget.month}")
                is BudgetRemoved -> requireAccess("budget:${e.categoryId}:${e.month}")
                is ExchangeRateOverrideSet -> requireAccess("rate:${e.currency}")
            }
        }
    }
}
