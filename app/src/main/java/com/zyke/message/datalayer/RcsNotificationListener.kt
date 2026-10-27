package com.zyke.message.datalayer

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

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

        RcsMessageStore.add(
            this,
            ZMessage(sender, body, sbn.postTime, ReadStatus.UNREAD, isRcs = true)
        )
    }

    companion object {
        val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
    }
}
