package com.zyke.message.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyke.message.R
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.data.diag.DevLog
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.device.CodeSharingFacts
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.sharing.CodeSharing
import com.zyke.message.domain.sharing.CodeSharingRules
import com.zyke.message.domain.sharing.SeenCodes
import com.zyke.message.domain.sharing.SeenOutcome
import com.zyke.message.domain.sharing.ShareHistory
import com.zyke.message.domain.sharing.ShareSources
import com.zyke.message.domain.sharing.SentRecord
import com.zyke.message.domain.sharing.ShareLog
import com.zyke.message.domain.sharing.ShareLogEntry
import com.zyke.message.domain.sharing.ShareStatus
import com.zyke.message.domain.sharing.TrustedContact
import com.zyke.message.presentation.MainActivity
import java.util.UUID

/**
 * Passes a login code that has just arrived on to the people the user chose, by SMS (see [CodeSharing] for the rules and
 * what is never passed on). It is called from [SmsReceiver] only, for a live message: never when the inbox is read.
 *
 * Nothing here writes a code, a phone number or message text anywhere: the developer log gets counts and reasons, the saved
 * log gets the service, the person's name, the time and the status, and the notifications name the service and the person.
 * Demo mode never gets here (the receiver and every entry point stop first).
 */
internal object CodeSharer {
    const val ACTION_STATUS = "com.zyke.message.action.CODE_SHARE_STATUS"
    const val EXTRA_LOG_ID = "log_id"
    const val EXTRA_KIND = "kind"
    const val KIND_SENT = "sent"
    const val KIND_DELIVERED = "delivered"
    private const val CHANNEL = "code_sharing"
    private const val NOTIFICATION_TIMEOUT_MS = 6L * 60 * 60 * 1000

    fun canSend(context: Context): Boolean =
        !DemoMode.active && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun canHear(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    /** What the phone allows right now, for [com.zyke.message.domain.device.CodeSharingHealth]. */
    fun facts(context: Context, container: AppContainer, now: Long = System.currentTimeMillis()): CodeSharingFacts {
        val rules = container.codeSharing.rules.value
        val receiverOn = context.packageManager.getComponentEnabledSetting(android.content.ComponentName(context, SmsReceiver::class.java)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        return CodeSharingFacts(
            switchedOn = rules.enabled,
            people = rules.activePeople,
            paused = CodeSharing.isPaused(rules, now),
            readingPaused = container.settings.settings.value.readingPaused,
            canHearTexts = canHear(context),
            canSendTexts = canSend(context),
            receiverOn = receiverOn
        )
    }

    /**
     * Called by the receiver, off the main thread, after the message was classified. Decides, then sends. Never throws:
     * whatever goes wrong with one person must not stop the next. Every code-like message is also noted in "Recent codes seen"
     * (the sender header, the time, the service and the outcome; never the code or the text).
     */
    fun onLiveMessage(context: Context, container: AppContainer, message: ClassifiedMessage) {
        if (DemoMode.active) return
        runCatching {
            handle(context, container, message, container.codeSharing.rules.value, test = null)
        }.onFailure { DevLog.d("sharing") { "code share failed: ${it.javaClass.simpleName}" } }
    }

    /**
     * The one path a message takes, for a real text and for "Send a test code" alike: review, then permission, then send, then
     * the note in "Recent codes seen". [test] is the one person a test goes to (with the made-up text), else null.
     * Returns how it ended, or null when there was nothing to say (sharing off, or not a code).
     */
    private fun handle(context: Context, container: AppContainer, message: ClassifiedMessage, rules: CodeSharingRules, test: TrustedContact?): SeenOutcome? {
        if (!rules.enabled) return null
        val now = System.currentTimeMillis()
        val repository = container.codeSharing
        val scoped = if (test == null) rules else rules.copy(contacts = listOf(test))
        val history = if (test == null) repository.history(now) else ShareHistory()
        val review = CodeSharing.review(message, scoped, now, history)
        val sender = message.message.sender
        fun note(outcome: SeenOutcome, detail: String = "") {
            if (!review.codeLike && test == null) return
            repository.addSeen(SeenCodes.entry(UUID.randomUUID().toString(), now, sender, review.service?.id, outcome, detail, test = test != null, label = review.label))
        }
        val decision = review.decision
        DevLog.d("sharing") { "code share: ${decision.shares.size} to send, skipped for ${decision.reasons.joinToString { it.name }.ifEmpty { "none" }}" }
        if (decision.shares.isEmpty()) {
            note(review.outcome)
            return review.outcome
        }
        if (!canSend(context)) {
            DevLog.d("sharing") { "no permission to send texts" }
            note(SeenOutcome.NO_PERMISSION)
            return SeenOutcome.NO_PERMISSION
        }
        val manager = smsManager(context)
        val sentTo = ArrayList<String>()
        var failed = false
        for (share in decision.shares) {
            runCatching {
                val entry = ShareLogEntry(UUID.randomUUID().toString(), share.source.id, share.contact.id, share.contact.name, now, ShareStatus.SENDING, test = test != null, label = share.source.label)
                repository.addLog(entry)
                if (test == null) repository.remember(SentRecord(share.contact.id, share.source.id, now, share.codeKey))
                val text = if (test != null) CodeSharing.testText(share.source) else share.text
                if (send(context, container, manager, share.contact, text, entry)) sentTo += share.contact.name else failed = true
            }.onFailure {
                failed = true
                DevLog.d("sharing") { "one send failed: ${it.javaClass.simpleName}" }
            }
        }
        val outcome = if (sentTo.isNotEmpty()) SeenOutcome.SENT else SeenOutcome.SEND_FAILED
        note(outcome, if (sentTo.isNotEmpty()) sentTo.joinToString(", ") else "the phone refused the text")
        if (failed && sentTo.isNotEmpty()) DevLog.d("sharing") { "some sends failed" }
        return outcome
    }

    enum class TestResult { QUEUED, NO_PERMISSION, TOO_SOON, FAILED }

    /** The harmless "Test from Zyke" text to one person, after the person confirmed it. */
    fun sendTest(context: Context, container: AppContainer, contact: TrustedContact): TestResult {
        if (DemoMode.active || !canSend(context)) return TestResult.NO_PERMISSION
        val now = System.currentTimeMillis()
        if (!CodeSharing.canSendTest(container.codeSharing.log.value, contact.id, now)) return TestResult.TOO_SOON
        val entry = ShareLogEntry(UUID.randomUUID().toString(), "", contact.id, contact.name, now, ShareStatus.SENDING, test = true)
        container.codeSharing.addLog(entry)
        return if (send(context, container, smsManager(context), contact, CodeSharing.TEST_TEXT, entry)) TestResult.QUEUED else TestResult.FAILED
    }

    /**
     * "Send a test code": a made-up message from the source [serviceId] goes through the same path as a real text (classify,
     * review, send) to this one person, and the text they get says "Zyke TEST". It follows the real rules, so a pause, the hours
     * or a missing permission show up as the reason instead of a send. Returns one plain sentence for the person.
     */
    fun sendTestCode(context: Context, container: AppContainer, contact: TrustedContact, serviceId: String): String {
        if (DemoMode.active) return "Not available in Demo mode"
        val service = ShareSources.find(serviceId, container.codeSharing.rules.value.sources) ?: return "Choose a sender first"
        if (service.isPhone) return "A test code can't be made for a phone number"
        val now = System.currentTimeMillis()
        if (!CodeSharing.canSendTest(container.codeSharing.log.value, contact.id, now)) return "A test was just sent: wait a minute"
        val rules = container.codeSharing.rules.value
        if (!rules.enabled) return "Switch Share codes on first"
        val fake = container.messageClassifier.classify(CodeSharing.testMessage(service, now))
        val outcome = runCatching { handle(context, container, fake, rules, test = contact) }.getOrNull() ?: return "Couldn't run the test"
        return SeenCodes.reason(SeenCodes.entry("", now, "", service.id, outcome, test = false), rules.sources)
            .let { if (outcome == SeenOutcome.SENT) "Test code sent to ${contact.name}" else it }
    }

    /**
     * Hands one short, plain text to the phone for [BalanceRequester] ("check balance by SMS"); the only other way a text leaves this app.
     * The person tapped Send on a screen that showed this number and this text. [report] gets the phone's "sent" report, which goes to
     * [CodeShareStatusReceiver]. False when it could not be queued. Nothing here is logged: not the number, not the words.
     */
    fun sendPlain(context: Context, number: String, text: String, report: PendingIntent): Boolean {
        if (DemoMode.active || !canSend(context)) return false
        val manager = smsManager(context) ?: return false
        return try {
            manager.sendTextMessage(number, null, text, report, null)
            true
        } catch (e: Exception) {
            // The class only: an exception's message could quote the number.
            DevLog.d("balance") { "request refused: ${e.javaClass.simpleName}" }
            false
        }
    }

    /** Hands the text to the phone. The sent and delivered reports come back to [CodeShareStatusReceiver]. */
    private fun send(context: Context, container: AppContainer, manager: SmsManager?, contact: TrustedContact, text: String, entry: ShareLogEntry): Boolean {
        if (manager == null) {
            failed(context, container, entry)
            return false
        }
        return try {
            val sent = statusIntent(context, entry.id, KIND_SENT)
            val delivered = statusIntent(context, entry.id, KIND_DELIVERED)
            val parts = manager.divideMessage(text)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(
                    contact.number, null, parts,
                    ArrayList<PendingIntent>(parts.map { sent }), ArrayList<PendingIntent>(parts.map { delivered })
                )
            } else {
                manager.sendTextMessage(contact.number, null, text, sent, delivered)
            }
            true
        } catch (e: Exception) {
            // The class only: an exception's message could quote the number.
            DevLog.d("sharing") { "send refused: ${e.javaClass.simpleName}" }
            failed(context, container, entry)
            false
        }
    }

    private fun failed(context: Context, container: AppContainer, entry: ShareLogEntry) {
        container.codeSharing.updateStatus(entry.id, ShareStatus.FAILED)
        post(context, entry.copy(status = ShareStatus.FAILED))
    }

    private fun statusIntent(context: Context, logId: String, kind: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, (logId + kind).hashCode(),
            Intent(context, CodeShareStatusReceiver::class.java).setAction(ACTION_STATUS).putExtra(EXTRA_LOG_ID, logId).putExtra(EXTRA_KIND, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** The SIM chosen for texts in the phone's settings, so a dual-SIM phone sends from the one the person expects. */
    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            val id = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(id) else SmsManager.getDefault()
        }
    }.getOrNull()

    /**
     * The quiet note after a forward: "Sent Netflix code to Sister", or "Couldn't send to Sister". It names the service and
     * the person and never the code; the lock screen shows only that something was shared.
     */
    fun post(context: Context, entry: ShareLogEntry) {
        if (!LiveAlerts.canNotify(context)) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(context)
            val accent = ContextCompat.getColor(context, R.color.zyke_accent)
            val failed = entry.status == ShareStatus.FAILED
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                ("share:" + entry.id).hashCode(),
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.zyke_icon)
                    .setColor(accent)
                    .setContentTitle(ShareLog.headline(entry))
                    .setContentText(if (failed) "Check that this phone can send texts" else "Shared by Zyke Message")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.zyke_icon).setColor(accent)
                            .setContentTitle("Code sharing").setContentText("Unlock to see").build()
                    )
                    .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
                    .build()
            )
        }
    }

    fun ensureChannel(context: Context) {
        if (DemoMode.active || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Shared codes", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note each time a login code is sent to someone you trust"
            }
        )
    }
}
