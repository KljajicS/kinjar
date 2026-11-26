package app.kinjar.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kinjar.data.db.Bank_alert
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.BankApp
import app.kinjar.domain.BankNotification
import app.kinjar.domain.BankNotificationParser
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class BankDraft(val alert: Bank_alert, val bank: BankApp, val parsed: BankNotification)

class BankInbox @Inject constructor(private val db: KinjarDb, private val store: EventStore) {
    val bindings = db.bankInboxQueries.bindings().asFlow().mapToList(Dispatchers.IO)
        .map { rows -> rows.associate { it.account_id to BankApp.valueOf(it.bank) } }

    val drafts = db.bankInboxQueries.pending().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { alert ->
            val bank = BankApp.valueOf(alert.bank)
            BankDraft(alert, bank, BankNotificationParser.parse(bank, alert.title, alert.body))
        }
    }

    suspend fun bind(accountId: String, bank: BankApp?) = withContext(Dispatchers.IO) {
        store.read {
            check(db.restoreJournalQueries.journalInfo().executeAsOneOrNull() == null)
            val account = db.accountQueries.byId(accountId).executeAsOneOrNull()
            require(account != null && account.archived == 0L)
            if (bank == null) db.bankInboxQueries.unbindAccount(accountId)
            else db.bankInboxQueries.bindAccount(accountId, bank.name)
        }
    }

    suspend fun skip(alertId: String) = withContext(Dispatchers.IO) {
        store.read {
            db.requirePendingBankAlert(alertId)
            db.bankInboxQueries.resolve("SKIPPED", null, alertId)
        }
    }

    fun capture(bank: BankApp, deliveryKey: String, receivedAt: Long, title: String, body: String) {
        val safeTitle = title.take(240)
        val safeBody = body.take(4096)
        if (!BankNotificationParser.shouldCapture(safeTitle, safeBody)) return
        store.read {
            val session = db.syncSessionQueries.current().executeAsOneOrNull()
            if (session != null && session.status != "ACTIVE") return@read
            if (db.restoreJournalQueries.journalInfo().executeAsOneOrNull() != null) return@read
            val inbox = db.bankInboxQueries
            if (inbox.enabledBank(bank.name).executeAsOne() == 0L) return@read
            val id = MessageDigest.getInstance("SHA-256").digest("${bank.name}\u0000$deliveryKey\u0000$receivedAt".toByteArray())
                .joinToString("") { "%02x".format(it) }
            inbox.capture(id, bank.name, safeTitle, safeBody, receivedAt)
        }
    }
}

internal fun KinjarDb.requirePendingBankAlert(alertId: String): Bank_alert {
    check(restoreJournalQueries.journalInfo().executeAsOneOrNull() == null)
    return requireNotNull(bankInboxQueries.pendingById(alertId).executeAsOneOrNull()) { "Bank alert is unavailable" }
}
