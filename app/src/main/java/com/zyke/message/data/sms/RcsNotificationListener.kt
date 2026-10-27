package com.zyke.message.data.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zyke.message.ZykeMessagesApplication

/** Captures incoming chat (RCS) messages from the default messaging apps' notifications. */
class RcsNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
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

    private companion object {
        val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
    }
}
