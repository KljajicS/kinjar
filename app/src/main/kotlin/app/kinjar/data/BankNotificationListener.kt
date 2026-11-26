package app.kinjar.data

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.kinjar.domain.BankApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BankNotificationListener : NotificationListenerService() {
    @Inject lateinit var inbox: BankInbox
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val bank = BankApp.entries.firstOrNull { it.packageName == sbn.packageName } ?: return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf(String::isNotBlank) ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (body.isBlank()) return
        scope.launch { inbox.capture(bank, sbn.key, sbn.postTime, title, body) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
