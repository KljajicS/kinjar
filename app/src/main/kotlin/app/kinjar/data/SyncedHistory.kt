package app.kinjar.data

import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class SyncedHistory(private val db: KinjarDb, private val projector: Projector, private val spool: HistorySpool) {
    /** The spool holding the synced baseline; a version 1 baseline blob is converted on first use. */
    fun baselineId(): String? {
        val row = db.syncBaselineQueries.baselineHistory().executeAsOneOrNull() ?: return null
        row.history_id?.let { return it }
        val stored = spool.import(HistoryCodec.decode(requireNotNull(db.syncBaselinePayload())))
        db.syncBaselineQueries.saveStored(stored.id)
        return stored.id
    }

    /** Local history in canonical order, one record at a time, as [snapshot] would list it. */
    fun forEachRecord(consumer: (HistoryRecord) -> Unit) {
        val baseline = baselineId()
        if (baseline == null) {
            db.eventQueries.allInOrder { id, deviceId, seq, occurredAt, _, payload ->
                consumer(HistoryRecord.Baseline(OperationEvent(id, deviceId, seq, occurredAt, EventJson.decodeFromString<Event>(payload))))
            }.executeAsList()
            return
        }
        spool.records(baseline).forEach(consumer)
        db.syncOperationQueries.all { _, _, _, payload -> consumer(HistoryRecord.Operation.of(payload)) }.executeAsList()
        db.syncQuarantineQueries.all { _, payload, reason -> consumer(HistoryRecord.Quarantine(QuarantinedOperation.from(payload, reason))) }.executeAsList()
    }

    fun summary(): HistorySummary {
        val writer = HistoryStream.Writer(object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        })
        forEachRecord(writer::write)
        return writer.finish()
    }

    fun snapshot(): FamilyHistory {
        val baseline = mutableListOf<OperationEvent>()
        val operations = mutableListOf<String>()
        val quarantine = mutableListOf<QuarantinedOperation>()
        forEachRecord { record ->
            when (record) {
                is HistoryRecord.Baseline -> baseline += record.event
                is HistoryRecord.Operation -> operations += record.payload
                is HistoryRecord.Quarantine -> quarantine += record.value
            }
        }
        return FamilyHistory(baseline = baseline, operations = operations, quarantine = quarantine)
    }

    /**
     * Replaces local history with [source], reading it twice at most and never holding its payloads together.
     * With [quarantineInvalid], operations that fail validation are quarantined and reported to [moved] by their bytes.
     */
    fun replace(source: HistorySource, quarantineInvalid: Boolean = false, moved: (ByteArray) -> Unit = {}) {
        val payments by lazy { paymentsIn(source) }
        db.syncOutboxQueries.deleteAll()
        db.syncOperationQueries.deleteAll()
        db.syncConflictQueries.deleteAll()
        db.syncCheckpointQueries.deleteAll()
        db.syncQuarantineQueries.deleteAll()
        db.eventQueries.deleteAll()
        val previous = db.syncBaselineQueries.baselineHistory().executeAsOneOrNull()?.history_id
        db.syncBaselineQueries.deleteAll()
        val baseline = spool.begin()
        var kind = 0
        source.records().forEach { record ->
            require(record.kind >= kind) { "History records out of order" }
            kind = record.kind
            when (record) {
                is HistoryRecord.Baseline -> { spool.write(baseline, record); insert(record.event) }
                is HistoryRecord.Quarantine -> quarantine(record.value)
                is HistoryRecord.Operation -> {
                    val bytes = record.bytes()
                    val operation = OperationCodec.decode(bytes)
                    if (quarantineInvalid) { if (!acceptOrQuarantine(bytes, operation) { payments[it] }) moved(bytes) }
                    else {
                        require(db.syncOperationQueries.byId(operation.id).executeAsOneOrNull() == null) { "Duplicate operation" }
                        validateResources(operation) { payments[it] }
                        db.syncOperationQueries.insert(operation.id, operation.installId, operation.sequence, bytes)
                        operation.events.forEach(::insert)
                    }
                }
            }
        }
        db.syncBaselineQueries.saveStored(spool.finish(baseline).id)
        previous?.let(spool::delete)
        rebuild()
    }

    fun replace(history: FamilyHistory, quarantineInvalid: Boolean = false) = replace(history.source(), quarantineInvalid)

    fun receive(payloads: List<ByteArray>, familyId: String, datasetId: String) {
        val incoming = payloads.map { bytes ->
            try { OperationCodec.decode(bytes) } catch (e: UnsupportedSyncVersion) { throw e }
            catch (_: IllegalArgumentException) { quarantine(QuarantinedOperation.from(bytes)); null }
        }
        requireNotNull(baselineId())
        // Every baseline and stored operation event is in the event table, so only payments need decoding.
        val payments by lazy {
            val found = mutableMapOf<String, LoanPayment>()
            db.eventQueries.allInOrder { _, _, _, _, type, payload ->
                if (type == "LoanPaymentAdded") (EventJson.decodeFromString<Event>(payload) as? LoanPaymentAdded)?.let { found[it.payment.id] = it.payment }
                Unit
            }.executeAsList()
            incoming.filterNotNull().forEach { op -> op.events.forEach { (it.event as? LoanPaymentAdded)?.let { e -> found[e.payment.id] = e.payment } } }
            found
        }
        incoming.zip(payloads).forEach { (operation, bytes) ->
            if (operation != null) {
                if (operation.familyId != familyId || operation.datasetId != datasetId) quarantine(QuarantinedOperation.from(bytes, "IDENTITY_MISMATCH"))
                else acceptOrQuarantine(bytes, operation) { payments[it] }
            }
        }
    }

    fun quarantine(value: QuarantinedOperation) {
        val bytes = value.bytes()
        db.syncQuarantineQueries.save(HistoryCodec.digest(bytes), bytes, value.reason)
    }

    /** False when [operation] was quarantined. */
    private fun acceptOrQuarantine(bytes: ByteArray, operation: SyncOperation, payment: (String) -> LoanPayment?): Boolean {
        try {
            val existing = db.syncOperationQueries.byId(operation.id).executeAsOneOrNull()
            if (existing != null) { require(existing.payload.contentEquals(bytes)); return true }
            validateResources(operation, payment)
            operation.events.forEach(::validateIdentity)
        } catch (_: IllegalArgumentException) { quarantine(QuarantinedOperation.from(bytes)); return false }
        db.syncOperationQueries.insert(operation.id, operation.installId, operation.sequence, bytes)
        operation.events.forEach(::insert)
        return true
    }

    /** Replay inputs without payload weight: collisions only inspect the events of single-event operations. */
    fun operationViews(): List<SyncOperation> = db.syncOperationQueries.all { _, _, _, payload ->
        OperationCodec.decode(payload).let { it.copy(familyId = "history", datasetId = "history", events = it.events.take(2)) }
    }.executeAsList()

    fun operation(id: String): SyncOperation = OperationCodec.decode(db.syncOperationQueries.byId(id).executeAsOne().payload)

    fun rebuild() {
        val baseline = requireNotNull(baselineId())
        val replay = ConflictResolver.replay(operationViews())
        projector.clear()
        val integrity = FinancialIntegrity()
        spool.records(baseline).chunked(5_000).forEach { chunk ->
            val events = chunk.map { (it as HistoryRecord.Baseline).event }
            integrity.applyAtomically(events.map { it.event }, strict = false)
            events.forEach { projector.apply(it.record()) }
        }
        integrity.validate()
        db.syncConflictQueries.deleteAll()
        replay.conflicts.forEach { db.syncConflictQueries.save(it, if (it in replay.invalid) "INVALID_FINANCIAL_CHANGE" else "CONCURRENT_CHANGE") }
        replay.waiting.forEach { db.syncConflictQueries.save(it, "WAITING_FOR_DEPENDENCY") }
        val invalid = mutableSetOf<String>()
        replay.applicable.forEach { view ->
            if (view.parents.any { it in invalid }) {
                invalid += view.id
                db.syncConflictQueries.save(view.id, "WAITING_FOR_DEPENDENCY")
            } else {
                val operation = operation(view.id)
                val next = runCatching { integrity.applyAtomically(operation.events.map { it.event }, strict = true) }
                if (next.isFailure) {
                    invalid += operation.id
                    db.syncConflictQueries.save(operation.id, "INVALID_FINANCIAL_CHANGE")
                } else {
                    operation.events.forEach { projector.apply(it.record()) }
                }
            }
        }
    }

    private fun insert(event: OperationEvent) {
        validateIdentity(event)
        val payload = EventJson.encodeToString(Event.serializer(), event.event)
        val prior = db.eventQueries.bySequence(event.deviceId, event.seq).executeAsOneOrNull()
        if (prior != null) {
            require(prior.id == event.id && prior.occurred_at == event.occurredAt && prior.payload == payload) { "Conflicting historical identity" }
            return
        }
        db.eventQueries.insert(event.id, event.deviceId, event.seq, event.occurredAt,
            EventJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content, payload)
    }

    private fun validateIdentity(event: OperationEvent) {
        val prior = db.eventQueries.byId(event.id).executeAsOneOrNull()
        require(prior == null || (prior.device_id == event.deviceId && prior.seq == event.seq)) { "Conflicting event id" }
        db.eventQueries.bySequence(event.deviceId, event.seq).executeAsOneOrNull()?.let {
            require(it.id == event.id && it.occurred_at == event.occurredAt && it.payload == EventJson.encodeToString(Event.serializer(), event.event)) { "Conflicting historical identity" }
        }
    }

    private fun paymentsIn(source: HistorySource): Map<String, LoanPayment> {
        val found = mutableMapOf<String, LoanPayment>()
        fun add(event: Event) { if (event is LoanPaymentAdded) found[event.payment.id] = event.payment }
        source.records().forEach { record ->
            when (record) {
                is HistoryRecord.Baseline -> add(record.event.event)
                is HistoryRecord.Operation -> runCatching { OperationCodec.decode(record.bytes()) }.getOrNull()?.events?.forEach { add(it.event) }
                is HistoryRecord.Quarantine -> {}
            }
        }
        return found
    }

    private fun validateResources(operation: SyncOperation, payment: (String) -> LoanPayment?) {
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
                is LoanPaymentRemoved -> payment(e.paymentId)?.let { requireAccess("loan:${it.loanId}"); requireAccess("transaction:${it.transactionId}") }
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
