package com.zyke.message.data.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.classification.UpiLiteNotification

/** Captures incoming chat (RCS) messages from the default messaging apps' notifications. */
class RcsNotificationListener : NotificationListenerService() {
    /** Notifications already in the shade when access is switched on, or the app is updated, would otherwise be missed. */
    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == GOOGLE_PAY) {
            keepUpiLiteTopUp(sbn)
            return
        }
        if (sbn.packageName !in MESSAGING_PACKAGES) return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.category != Notification.CATEGORY_MESSAGE) return

        val extras = notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (sender.isNullOrBlank() || body.isNullOrBlank()) return

        (application as ZykeMessagesApplication).container.rcsStore.add(sender, body, sbn.postTime)
    }

    /** A Google Pay "UPI Lite refilled" notification becomes a message, so the top-up is counted as spent. */
    private fun keepUpiLiteTopUp(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val amount = UpiLiteNotification.amount(title, text) ?: return
        (application as ZykeMessagesApplication).container.rcsStore.add(UpiLiteNotification.SENDER, UpiLiteNotification.body(amount), sbn.postTime)
    }

    private companion object {
        const val GOOGLE_PAY = "com.google.android.apps.nbu.paisa.user"

        val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
    }
}
