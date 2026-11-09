package com.zyke.message.data.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.classification.UpiLiteNotification
import com.zyke.message.domain.mms.NotificationEntry
import com.zyke.message.domain.mms.NotificationFields
import com.zyke.message.domain.mms.RichNotificationText

/** Captures incoming chat (RCS) messages from the default messaging apps' notifications. */
class RcsNotificationListener : NotificationListenerService() {
    /** Notifications already in the shade when access is switched on, or the app is updated, would otherwise be missed. */
    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { handle(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        // Paused: nothing is read from notifications either.
        // Demo mode reads no other app's notifications.
        if (com.zyke.message.data.demo.DemoMode.active) return
        if ((application as com.zyke.message.ZykeMessagesApplication).container.settings.settings.value.readingPaused) return
        // A UPI Lite top-up is recognised by what it says, whichever Google Pay app or profile posted it.
        if (sbn.packageName != packageName && keepUpiLiteTopUp(sbn)) return
        if (sbn.packageName !in MESSAGING_PACKAGES) return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.category != Notification.CATEGORY_MESSAGE) return

        val captured = RichNotificationText.capture(fieldsOf(notification.extras), sbn.postTime)
        if (captured.isEmpty()) return

        val store = (application as ZykeMessagesApplication).container.rcsStore
        captured.forEach { chat ->
            com.zyke.message.data.diag.DevLog.d("rcs") { "chat notification kept from ${sbn.packageName}: title ${chat.sender.length} chars, text ${chat.body.length} chars" }
            store.add(chat.sender, chat.body, chat.at)
        }
    }

    /**
     * What a chat notification holds besides its short text: the expanded text, the inbox-style lines and the messaging-style entries
     * (Notification.EXTRA_MESSAGES, each a bundle with "text" and "time"). A rich message's text may be only in those.
     */
    private fun fieldsOf(extras: android.os.Bundle): NotificationFields {
        @Suppress("DEPRECATION")
        val entries = runCatching {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { parcel ->
                val bundle = parcel as? android.os.Bundle ?: return@mapNotNull null
                val text = bundle.getCharSequence("text")?.toString() ?: return@mapNotNull null
                NotificationEntry(text, bundle.getLong("time", 0L))
            }
        }.getOrNull().orEmpty()
        return NotificationFields(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
            entries = entries
        )
    }

    /** A Google Pay "UPI Lite refilled" notification becomes a message, so the top-up is counted as spent. */
    private fun keepUpiLiteTopUp(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        // The short and the expanded text are tried in turn; either may be the one that carries the amount.
        val amount = UpiLiteNotification.amount(title, text) ?: UpiLiteNotification.amount(title, big) ?: return false
        com.zyke.message.data.diag.DevLog.d("rcs") { "UPI Lite top-up kept from ${sbn.packageName}" }
        (application as ZykeMessagesApplication).container.rcsStore.add(UpiLiteNotification.SENDER, UpiLiteNotification.body(amount), sbn.postTime)
        return true
    }

    private companion object {

        val MESSAGING_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
    }
}
