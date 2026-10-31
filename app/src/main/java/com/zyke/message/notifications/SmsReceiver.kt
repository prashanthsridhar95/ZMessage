package com.zyke.message.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.model.SmsMessage
import kotlinx.coroutines.launch

/**
 * Hears about each new text, only after the person turned on live alerts (the receiver is disabled until then).
 * It does not store or forward anything: it reads the text in memory, and if it is a code or a card bill it
 * posts a button notification. The messaging app remains the default and keeps handling the message itself.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val container = (context.applicationContext as ZykeMessagesApplication).container
        if (!container.settings.settings.value.liveAlerts || container.settings.settings.value.readingPaused) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long text arrives in pieces from one sender; join them.
        val sender = parts.firstOrNull()?.originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val received = SmsMessage(0L, sender, body, System.currentTimeMillis(), isRead = false)

        com.zyke.message.data.diag.DevLog.d("receiver") { "SMS received from ${com.zyke.message.domain.diag.DiagLog.key(sender)} (${body.length} characters)" }
        val classified = container.messageClassifier.classify(received)
        LiveAlerts.post(context, classified)
        DaysAheadWidget.refresh(context)

        // The heads-up reads the inbox for what is usual, which takes a moment; keep the receiver alive for it.
        if (!container.settings.settings.value.headsUp) return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                HeadsUpNotifier.check(context, classified)
            } finally {
                pending.finish()
            }
        }
    }
}
