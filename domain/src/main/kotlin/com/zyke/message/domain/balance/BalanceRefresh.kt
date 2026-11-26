package com.zyke.message.domain.balance

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Why one account was not asked in a refresh before the digest. */
enum class RefreshSkip(val text: String) {
    NOT_SET_UP("no balance number set for this bank"),
    NOT_CONFIRMED("confirm it once with Check balance first"),
    ALREADY_TODAY("already checked today"),
    TOO_SOON("checked less than 6 hours ago"),
    TOO_LATE("too close to the digest"),
    COVERED("same text as another account"),
    LIMIT("more accounts than one refresh asks"),
    NO_PERMISSION("texting is not allowed"),
    NO_SMS("this phone cannot send texts"),
    AIRPLANE("airplane mode"),
    READING_PAUSED("reading is paused"),
    UNAVAILABLE("could not be asked")
}

/** One bank account as the refresh sees it. [request] is the number and words from its ready plan, or null when it has none. */
data class RefreshCandidate(
    val key: String,
    /** What the log calls it, such as "HDFC Bank •••• 5765". */
    val label: String,
    /** The bank's own balance number and the words, joined; used only to avoid sending the same text twice. Never logged or saved. */
    val request: String?,
    /** The person confirmed this bank once with "Always send without asking". */
    val confirmed: Boolean
)

/** What the phone allows right now. */
data class RefreshFacts(val canSend: Boolean, val hasSms: Boolean, val airplane: Boolean, val readingPaused: Boolean)

/** What happened to one account in one refresh. [askedAt] is null when it was skipped. */
data class RefreshEntry(
    val key: String,
    val label: String,
    val askedAt: Long?,
    val skip: RefreshSkip? = null,
    /** The phone could not queue the text. */
    val failed: Boolean = false
)

/** One refresh, kept until the next: the digest it was for and what each account got. Holds no number, no words, no reply. */
data class RefreshRun(val digestAt: Long, val ranAt: Long, val entries: List<RefreshEntry>)

enum class RefreshOutcome { REPLIED, WAITING, NO_REPLY, FAILED, SKIPPED }

/**
 * "Refresh balances before my digest" (off by default). A few minutes before the morning digest, Zyke sends each bank account's
 * own balance-enquiry text (the number and words the person confirmed under Check balance), the bank's replies arrive as ordinary
 * texts that the usual pipeline reads, and the digest goes out on time with whatever has arrived. Pure rules, tested.
 */
object BalanceRefresh {
    /** The refresh runs this long before the digest. */
    const val LEAD_MS = 5L * 60_000L
    /** Replies are counted for this long after a text was sent. */
    const val WINDOW_MS = 3L * 60_000L
    /** Never twice within this time (and at most once per calendar day). */
    const val MIN_SPACING_MS = 6L * 60L * 60_000L
    /** A refresh that wakes later than this before the digest sends nothing: the reply could not make it. */
    const val MIN_REMAINING_MS = 2L * 60_000L
    /** At most this many texts in one refresh. */
    const val MAX_ASKS = 6
    /** The digest never waits longer than this for a late reply (a receiver has to finish quickly); it goes out as scheduled. */
    const val DIGEST_WAIT_CAP_MS = 30_000L
    /** A run counts for the digest it was made for, up to this long afterwards. */
    const val RUN_VALID_MS = 3L * 60L * 60_000L

    /** When the refresh alarm goes: [LEAD_MS] before the next digest, or before tomorrow's when that moment has passed. */
    fun alarmAt(now: Long, digestAt: Long, digestTomorrowAt: Long): Long =
        (digestAt - LEAD_MS).takeIf { it > now } ?: (digestTomorrowAt - LEAD_MS)

    /** The reason nothing at all can be asked, or null. */
    fun blockedForAll(facts: RefreshFacts): RefreshSkip? = when {
        facts.readingPaused -> RefreshSkip.READING_PAUSED
        !facts.canSend -> RefreshSkip.NO_PERMISSION
        !facts.hasSms -> RefreshSkip.NO_SMS
        facts.airplane -> RefreshSkip.AIRPLANE
        else -> null
    }

    /** Why this account is not asked now, or null when it may be. A request that failed does not count. */
    fun skipFor(candidate: RefreshCandidate, last: BalanceRequest?, now: Long, digestAt: Long, zone: ZoneId): RefreshSkip? {
        if (candidate.request == null) return RefreshSkip.NOT_SET_UP
        if (!candidate.confirmed) return RefreshSkip.NOT_CONFIRMED
        if (digestAt - now < MIN_REMAINING_MS) return RefreshSkip.TOO_LATE
        if (last != null && last.status != RequestStatus.FAILED) {
            if (now - last.sentAt < MIN_SPACING_MS) return RefreshSkip.TOO_SOON
            if (day(last.sentAt, zone) == day(now, zone)) return RefreshSkip.ALREADY_TODAY
        }
        return null
    }

    /**
     * What a refresh does with every account, in the order given: who is asked (a non-null [RefreshEntry.askedAt]) and who is skipped
     * and why. Two accounts of one bank that would get the same text are asked once; the second says it is covered.
     */
    fun plan(
        candidates: List<RefreshCandidate>, requests: Map<String, BalanceRequest>, now: Long, digestAt: Long, zone: ZoneId, blocked: RefreshSkip?
    ): List<RefreshEntry> {
        val sent = mutableSetOf<String>()
        var asks = 0
        return candidates.map { c ->
            val skip = blocked ?: skipFor(c, requests[c.key], now, digestAt, zone) ?: when {
                c.request in sent -> RefreshSkip.COVERED
                asks >= MAX_ASKS -> RefreshSkip.LIMIT
                else -> null
            }
            if (skip == null) { sent += c.request.orEmpty(); asks += 1 }
            RefreshEntry(c.key, c.label, if (skip == null) now else null, skip)
        }
    }

    /** Where one entry stands. [balanceAt] is the newest balance reading for the account (epoch millis, 0 when none). */
    fun outcome(entry: RefreshEntry, balanceAt: Long, now: Long): RefreshOutcome {
        val asked = entry.askedAt
        return when {
            entry.skip != null || asked == null -> RefreshOutcome.SKIPPED
            entry.failed -> RefreshOutcome.FAILED
            balanceAt >= asked -> RefreshOutcome.REPLIED
            now - asked < WINDOW_MS -> RefreshOutcome.WAITING
            else -> RefreshOutcome.NO_REPLY
        }
    }

    /** "Balances refreshed for 2 of 3 accounts", counting only accounts that were asked; null when none was. */
    fun summary(outcomes: List<RefreshOutcome>): String? {
        val asked = outcomes.count { it != RefreshOutcome.SKIPPED }
        if (asked == 0) return null
        val replied = outcomes.count { it == RefreshOutcome.REPLIED }
        return "Balances refreshed for $replied of $asked ${if (asked == 1) "account" else "accounts"}"
    }

    /**
     * How long the digest may wait for replies still due: the rest of the window, never more than [DIGEST_WAIT_CAP_MS], and 0
     * when nobody is waiting, so the digest is never held past its time by more than that.
     */
    fun digestWaitMs(run: RefreshRun?, balanceAtByKey: Map<String, Long>, now: Long): Long {
        if (run == null) return 0L
        val left = run.entries.filter { outcome(it, balanceAtByKey[it.key] ?: 0L, now) == RefreshOutcome.WAITING }
            .maxOfOrNull { (it.askedAt ?: 0L) + WINDOW_MS - now } ?: return 0L
        return left.coerceIn(0L, DIGEST_WAIT_CAP_MS)
    }

    /** Whether [run] was made for a digest at about [now]. */
    fun isCurrent(run: RefreshRun?, now: Long): Boolean = run != null && now - run.digestAt in -LEAD_MS..RUN_VALID_MS

    /** "Asked 8:55 AM · reply 8:56 AM", "Asked 8:55 AM · no reply", "Skipped · already checked today". */
    fun logLine(entry: RefreshEntry, outcome: RefreshOutcome, balanceAt: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val format = DateTimeFormatter.ofPattern("h:mm a", locale)
        fun at(millis: Long) = format.format(Instant.ofEpochMilli(millis).atZone(zone))
        val asked = entry.askedAt?.let { "Asked ${at(it)}" }.orEmpty()
        return when (outcome) {
            RefreshOutcome.SKIPPED -> "Skipped · ${entry.skip?.text ?: RefreshSkip.UNAVAILABLE.text}"
            RefreshOutcome.FAILED -> "$asked · could not send"
            RefreshOutcome.REPLIED -> "$asked · reply ${at(balanceAt)}"
            RefreshOutcome.WAITING -> "$asked · waiting for the reply"
            RefreshOutcome.NO_REPLY -> "$asked · no reply"
        }
    }

    private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
