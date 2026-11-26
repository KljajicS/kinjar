package app.kinjar.data

import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.BankApp
import app.kinjar.domain.BankNotificationParser
import java.security.MessageDigest
import javax.inject.Inject

class BankInbox @Inject constructor(private val db: KinjarDb, private val store: EventStore) {
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
