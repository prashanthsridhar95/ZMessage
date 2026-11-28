package com.zyke.message.domain.email

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.BankTransaction
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.UserMessageStateRepository
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class EmailAlertKind { DEBIT, CREDIT, UNCLEAR }

/**
 * What the app keeps of one bank alert email: the fields read from it and a short masked snippet. Never the email: not the
 * subject, not the address it came from, not the body, not the recipient. The snippet has its digits, names, links and handles masked
 * the way the parsing report masks them, so it can be shown or shared without handing over an account number.
 */
data class EmailAlert(
    /** Stable for one email (from its Message-ID, else its place in the mailbox); the same email read twice is one alert. */
    val key: String,
    /** [AlertSender.id] of the listed address it came from. */
    val senderId: String,
    val bank: BankName,
    /** What the message list calls it: "HDFC Bank". */
    val senderName: String,
    /** When the email was sent (its Date header), in epoch milliseconds. */
    val at: Long,
    val kind: EmailAlertKind,
    /** Digits and an optional decimal point, no separators; empty for [EmailAlertKind.UNCLEAR]. */
    val amount: String,
    /** The shop or person, or the UPI address when no name was given. */
    val payee: String,
    /** The UPI address, or empty. */
    val vpa: String,
    /** The last four digits of the account or card, or empty. */
    val account: String,
    val isCard: Boolean,
    /** The UPI or bank reference number (9 to 16 digits), or empty. */
    val reference: String,
    /** At most [SNIPPET_MAX] characters, masked. */
    val snippet: String
) : java.io.Serializable {
    companion object {
        const val SNIPPET_MAX = 240
    }
}

/** How often the app may ask the mailbox when it is opened. There is no timer and no background service. */
enum class EmailSyncEvery(val id: String, val label: String, val minGapMs: Long) {
    MANUAL("manual", "Only when I tap Sync now", Long.MAX_VALUE),
    HOURLY("hourly", "When the app opens, at most once an hour", 60L * 60 * 1000),
    DAILY("daily", "When the app opens, at most once a day", 24L * 60 * 60 * 1000);

    companion object {
        fun fromId(id: String): EmailSyncEvery = entries.firstOrNull { it.id == id } ?: HOURLY
    }
}

/** What the last attempt to read the mailbox ended with, for the status card. */
enum class SyncOutcome { OK, WRONG_PASSWORD, PROVIDER_BLOCKED, NETWORK, TLS, TIMEOUT, PROTOCOL }

/** The choices about email alerts, all in one record. Describes this phone: never in a backup. */
data class EmailAlertSettings(
    val enabled: Boolean = false,
    /** The one-time explanation was shown and accepted. */
    val explained: Boolean = false,
    val providerId: String = "",
    /** The person's own address, shown in Settings so they know which mailbox is connected. Never sent anywhere but to the provider. */
    val address: String = "",
    /** The last sign-in worked and a password is saved. */
    val connected: Boolean = false,
    val syncEvery: EmailSyncEvery = EmailSyncEvery.HOURLY,
    val keepMonths: Int = DEFAULT_KEEP_MONTHS,
    val lastSyncAt: Long = 0L,
    val lastOutcome: SyncOutcome? = null,
    /** How many alerts the last pass added. */
    val lastAdded: Int = 0,
    val uidValidity: Long = 0L,
    val highestUid: Long = 0L,
    /** The person answered the "HDFC is stopping UPI SMS alerts" offer (either way), so it is not shown again. */
    val hdfcNoticeAnswered: Boolean = false
) {
    val provider: EmailProvider? get() = EmailProvider.fromId(providerId)

    companion object {
        const val DEFAULT_KEEP_MONTHS = 12
        val KEEP_CHOICES = listOf(3, 6, 12, 24)
    }
}

data class EmailAlertState(val settings: EmailAlertSettings = EmailAlertSettings(), val alerts: List<EmailAlert> = emptyList()) {
    val understood: Int get() = alerts.count { it.kind != EmailAlertKind.UNCLEAR }
    val notUnderstood: Int get() = alerts.count { it.kind == EmailAlertKind.UNCLEAR }
}

/** Where the settings and the parsed alerts are kept (private preferences in the app; nothing in the demo). */
interface EmailAlertRepository {
    val state: StateFlow<EmailAlertState>

    fun update(change: (EmailAlertSettings) -> EmailAlertSettings)

    /** Adds alerts not already kept (by key), keeps the newest [MAX_ALERTS], and drops those older than the keep-for setting. */
    fun add(alerts: List<EmailAlert>, now: Long)

    /** Drops alerts older than the keep-for setting. */
    fun prune(now: Long)

    /** Removes every kept alert and the place reached in the mailbox, so a later sync reads the mailbox again. The connection stays. */
    fun clearAlerts()

    /** Removes the alerts and every setting: used by Disconnect. */
    fun clearAll()

    companion object {
        const val MAX_ALERTS = 2000
    }
}

/** Nothing is kept and nothing is on: the demo and tests that do not care. */
object NoEmailAlerts : EmailAlertRepository {
    override val state: StateFlow<EmailAlertState> = MutableStateFlow(EmailAlertState())
    override fun update(change: (EmailAlertSettings) -> EmailAlertSettings) = Unit
    override fun add(alerts: List<EmailAlert>, now: Long) = Unit
    override fun prune(now: Long) = Unit
    override fun clearAlerts() = Unit
    override fun clearAll() = Unit
}

/** The rules every store shares, so the preferences-backed one and the in-memory one behave the same. */
object EmailAlertRules {
    private const val DAYS_PER_MONTH = 30L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun merge(existing: List<EmailAlert>, added: List<EmailAlert>, keepMonths: Int, now: Long): List<EmailAlert> {
        val byKey = LinkedHashMap<String, EmailAlert>()
        for (a in existing) byKey[a.key] = a
        for (a in added) byKey[a.key] = a
        return pruned(byKey.values.toList(), keepMonths, now).sortedByDescending { it.at }.take(EmailAlertRepository.MAX_ALERTS)
    }

    fun pruned(alerts: List<EmailAlert>, keepMonths: Int, now: Long): List<EmailAlert> {
        if (keepMonths <= 0) return alerts
        val cutoff = now - keepMonths * DAYS_PER_MONTH * DAY_MS
        return alerts.filter { it.at >= cutoff }
    }
}

/** A store in memory: tests, and the base of the app's preferences-backed one. */
class InMemoryEmailAlerts(initial: EmailAlertState = EmailAlertState()) : EmailAlertRepository {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<EmailAlertState> = flow

    override fun update(change: (EmailAlertSettings) -> EmailAlertSettings) {
        flow.value = flow.value.copy(settings = change(flow.value.settings))
    }

    override fun add(alerts: List<EmailAlert>, now: Long) {
        val s = flow.value
        flow.value = s.copy(alerts = EmailAlertRules.merge(s.alerts, alerts, s.settings.keepMonths, now))
    }

    override fun prune(now: Long) {
        val s = flow.value
        flow.value = s.copy(alerts = EmailAlertRules.pruned(s.alerts, s.settings.keepMonths, now))
    }

    override fun clearAlerts() {
        val s = flow.value
        flow.value = s.copy(alerts = emptyList(), settings = s.settings.copy(uidValidity = 0L, highestUid = 0L, lastAdded = 0))
    }

    override fun clearAll() {
        flow.value = EmailAlertState()
    }
}

/** Turns kept alerts into the messages the rest of the app already knows how to count, categorise, name and link. */
object EmailAlerts {
    /** Ids of email messages start here: above every SMS provider id and apart from the negative ids of chat, picture and hand-made messages. */
    const val ID_BASE: Long = 1L shl 52
    private const val ID_SPAN: Long = 1L shl 48
    private const val ID_BYTES = 6

    fun idFor(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until ID_BYTES) v = (v shl 8) or (digest[i].toLong() and 0xFF)
        return ID_BASE + v
    }

    fun isEmailId(messageId: Long): Boolean = messageId >= ID_BASE && messageId < ID_BASE + ID_SPAN

    /** A stable key for one email: its Message-ID when it has one, else the mailbox and the number the mailbox gave it. */
    fun keyOf(providerId: String, uidValidity: Long, uid: Long, messageId: String): String =
        if (messageId.isNotBlank()) "m:" + sha(messageId.trim().lowercase()) else "u:$providerId:$uidValidity:$uid"

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(HASH_BYTES).joinToString("") { "%02x".format(it) }

    private const val HASH_BYTES = 12

    /**
     * The words a message made from [alert] shows and is read by: in the shape of a bank's own text (amount, account, UPI address,
     * date, "UPI Ref No."), so the one-payment check can find the same reference number in an SMS about the same payment.
     */
    fun bodyOf(alert: EmailAlert, zone: ZoneId): String {
        if (alert.kind == EmailAlertKind.UNCLEAR) return "Email from ${alert.senderName}, not understood: ${alert.snippet}"
        val date = DateTimeFormatter.ofPattern("dd-MM-yy", Locale.ROOT).format(Instant.ofEpochMilli(alert.at).atZone(zone))
        val amount = "Rs.${alert.amount}"
        val account = when {
            alert.account.isEmpty() -> alert.senderName
            alert.isCard -> "${alert.senderName} Card XX${alert.account}"
            else -> "${alert.senderName} A/c XX${alert.account}"
        }
        val party = when {
            alert.vpa.isNotEmpty() && alert.payee != alert.vpa -> "VPA ${alert.vpa} (${alert.payee})"
            alert.vpa.isNotEmpty() -> "VPA ${alert.vpa}"
            else -> alert.payee
        }
        val ref = when {
            alert.reference.isEmpty() -> ""
            alert.vpa.isNotEmpty() -> " UPI Ref No. ${alert.reference}."
            else -> " Ref No. ${alert.reference}."
        }
        val tail = if (party.isEmpty()) "" else if (alert.kind == EmailAlertKind.DEBIT) " to $party" else " from $party"
        val verb = if (alert.kind == EmailAlertKind.DEBIT) "debited from" else "credited to"
        return "$amount $verb $account$tail on $date.$ref"
    }

    /**
     * The message for [alert]. A payment is read by the same code that reads one the person taught the app, so it is counted,
     * categorised and named like any other. A mail that was not understood is a plain message: it is never read as a payment,
     * and it shows in "Messages not understood".
     */
    fun toMessage(alert: EmailAlert, classifier: MessageClassifier, zone: ZoneId): ClassifiedMessage {
        val sms = SmsMessage(idFor(alert.key), alert.senderName, bodyOf(alert, zone), alert.at, isRead = true, isEmail = true)
        if (alert.kind == EmailAlertKind.UNCLEAR) {
            return ClassifiedMessage(
                message = sms,
                category = MessageCategory.OTHERS,
                merchant = Merchant(alert.senderName, MerchantLogo.PERSON, MessageCategory.OTHERS),
                content = MessageContent.Generic
            )
        }
        val type = if (alert.kind == EmailAlertKind.DEBIT) BankTransactionType.SENT else BankTransactionType.RECEIVED
        val tx = BankTransaction(alert.bank, alert.account, alert.payee, alert.amount, type)
        return classifier.fromLearned(sms, tx)
    }
}

/**
 * The person's read, pinned and archived marks are dropped for any message no longer in the phone's inbox. Email messages are not in
 * that inbox, so this keeps their marks: every call to [retainOnly] also keeps the ids of the alerts currently held.
 */
class KeepEmailMarks(
    private val inner: UserMessageStateRepository,
    private val alerts: () -> List<EmailAlert>
) : UserMessageStateRepository by inner {
    override fun retainOnly(existingIds: Set<Long>) {
        val mine = alerts().map { EmailAlerts.idFor(it.key) }
        inner.retainOnly(if (mine.isEmpty()) existingIds else existingIds + mine)
    }
}
