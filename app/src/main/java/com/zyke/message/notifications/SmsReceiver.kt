package com.zyke.message.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.model.SmsMessage
import kotlinx.coroutines.launch

/**
 * Hears about each new text, only after the person turned on live alerts or code sharing (the receiver is disabled until
 * then). It stores nothing: it reads the text in memory, and if it is a code or a card bill it posts a button notification.
 * When the person set up "Share codes with trusted people", a login code from an allowed service is also sent on by SMS to
 * the chosen people ([CodeSharer]: the code and the service name only, never the message). The messaging app remains the default and keeps handling the message itself.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // Demo mode: a new real message is neither read nor announced.
        if (com.zyke.message.data.demo.DemoMode.active) return
        val container = (context.applicationContext as ZykeMessagesApplication).container
        // The receiver is on for the code and bill buttons, for passing codes on to trusted people, or both.
        val settings = container.settings.settings.value
        val alerts = settings.liveAlerts
        val sharing = container.codeSharing.rules.value.enabled
        // A time only, before anything else can return: it lets the sharing page show whether texts reach this receiver at all.
        if (sharing) runCatching { container.codeSharing.noteReceiverFired(System.currentTimeMillis()) }
        if ((!alerts && !sharing) || settings.readingPaused) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long text arrives in pieces from one sender; join them.
        val sender = parts.firstOrNull()?.originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val received = SmsMessage(0L, sender, body, System.currentTimeMillis(), isRead = false)

        com.zyke.message.data.diag.DevLog.d("receiver") { "SMS received from ${com.zyke.message.domain.diag.DiagLog.key(sender)} (${body.length} characters)" }
        val classified = container.messageClassifier.classify(received)
        if (alerts) {
            // One failure to post (or to refresh the widget) must never stop the rest.
            runCatching { LiveAlerts.post(context, classified) }.onFailure { com.zyke.message.data.diag.DevLog.d("receiver") { "alert not posted: ${it.javaClass.simpleName}" } }
            runCatching { DaysAheadWidget.refresh(context) }
        }

        // Passing a code on (this live message only, never a re-read of the inbox) and the heads-up (which reads the inbox for
        // what is usual) take a moment; keep the receiver alive for them.
        val headsUp = alerts && settings.headsUp
        if (!sharing && !headsUp) return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                if (sharing) CodeSharer.onLiveMessage(context, container, classified)
                if (headsUp) HeadsUpNotifier.check(context, classified)
            } finally {
                pending.finish()
            }
        }
    }
}
