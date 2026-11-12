package com.zyke.message.domain.sharing

/** What [CodeSharing.review] found: the decision, and what the "Recent codes seen" log needs. [code] is never logged. */
data class ShareReview(
    val decision: ShareDecision,
    /** Looked like a login code, or came from a sender named like one of the services: worth a line in the log. */
    val codeLike: Boolean,
    /** The source it was taken for, or null (a sender nobody chose). */
    val service: ShareSource?,
    val outcome: SeenOutcome,
    /** Held in memory for the send only; never put in [SeenCode]. */
    val code: String?,
    /** What to call the sender in the log: the source's label, else the service named in the code, else the sender's name. */
    val label: String = ""
)

/** How a code-like message ended. */
enum class SeenOutcome {
    SENT, OFF, NOT_SERVICE, FINANCIAL, ACCOUNT_SECURITY, NO_ONE_FOR_SERVICE, PERSON_OFF, BAD_NUMBER, PAUSED, OUTSIDE_HOURS,
    RATE_LIMITED, ALREADY_SENT, STALE, NO_PERMISSION, NOT_READ_AS_CODE, SEND_FAILED
}

/**
 * One line of "Recent codes seen". It holds a time, the sender HEADER (a phone number is masked to its last two digits), the
 * service it was taken for and the outcome: never the code, the message text or a number. [detail] is only the names of the
 * people it was sent to, or a short word for why a send failed.
 */
data class SeenCode(
    val id: String,
    val at: Long,
    val header: String,
    val serviceId: String?,
    val outcome: SeenOutcome,
    val detail: String = "",
    val test: Boolean = false,
    /** The sender's name for the row ("JioHotstar"); a name only. */
    val label: String = ""
)

object SeenCodes {
    const val MAX_ENTRIES = 20
    private const val MAX_HEADER = 24
    private const val MAX_DETAIL = 80

    fun outcomeOf(reason: ShareSkip, codeLike: Boolean): SeenOutcome = when (reason) {
        ShareSkip.OFF -> SeenOutcome.OFF
        ShareSkip.PAUSED -> SeenOutcome.PAUSED
        ShareSkip.OUTSIDE_HOURS -> SeenOutcome.OUTSIDE_HOURS
        ShareSkip.FINANCIAL -> SeenOutcome.FINANCIAL
        ShareSkip.ACCOUNT_SECURITY -> SeenOutcome.ACCOUNT_SECURITY
        ShareSkip.NOT_A_CODE -> SeenOutcome.NOT_READ_AS_CODE
        ShareSkip.STALE -> SeenOutcome.STALE
        ShareSkip.NOT_AN_ALLOWED_SERVICE -> SeenOutcome.NOT_SERVICE
        ShareSkip.NO_ONE_FOR_SERVICE -> SeenOutcome.NO_ONE_FOR_SERVICE
        ShareSkip.PERSON_OFF -> SeenOutcome.PERSON_OFF
        ShareSkip.BAD_NUMBER -> SeenOutcome.BAD_NUMBER
        ShareSkip.RATE_LIMITED -> SeenOutcome.RATE_LIMITED
        ShareSkip.ALREADY_SENT -> SeenOutcome.ALREADY_SENT
    }

    /**
     * The header as it may be shown and kept. A service's header ("VM-NFLXIN-S") is not personal data; a phone number is, so a
     * header with six or more digits keeps only its last two ("••••••10"). Only letters, digits and a few marks survive.
     */
    fun header(sender: String): String {
        val raw = sender.trim()
        val digits = raw.count { it in '0'..'9' }
        if (digits >= 6) return "••••••" + raw.filter { it in '0'..'9' }.takeLast(2)
        return raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' || it == '&' }.take(MAX_HEADER)
    }

    /** Names and short words only: no run of five digits or more can get through, so a number typed as a name is not kept. */
    fun cleanDetail(raw: String): String =
        raw.filter { it.isLetter() || it in '0'..'9' || it == ' ' || it == ',' || it == '\'' || it == '.' || it == '-' }
            .replace(Regex("[0-9]{5,}"), "").replace(Regex("\\s+"), " ").trim().take(MAX_DETAIL)

    fun entry(id: String, at: Long, sender: String, serviceId: String?, outcome: SeenOutcome, detail: String = "", test: Boolean = false, label: String = ""): SeenCode =
        SeenCode(id, at, header(sender), serviceId, outcome, cleanDetail(detail), test, ShareSources.cleanLabel(label))

    /** The newest first, at most [MAX_ENTRIES]. */
    fun add(list: List<SeenCode>, entry: SeenCode): List<SeenCode> =
        (listOf(entry) + list.filter { it.id != entry.id }).sortedByDescending { it.at }.take(MAX_ENTRIES)

    /** The plain sentence next to a row. */
    fun reason(e: SeenCode, sources: List<ShareSource> = emptyList()): String {
        val label = nameOf(e, sources) ?: "that sender"
        val text = when (e.outcome) {
            SeenOutcome.SENT -> "Sent to ${e.detail.ifEmpty { "your people" }}"
            SeenOutcome.OFF -> "Not forwarded: code sharing is off"
            SeenOutcome.NOT_SERVICE -> "Not forwarded: couldn't tell which service this is"
            SeenOutcome.FINANCIAL -> "Not forwarded: looks like a payment or bank code"
            SeenOutcome.ACCOUNT_SECURITY -> "Not forwarded: looks like a government, insurance or phone-company code"
            SeenOutcome.NO_ONE_FOR_SERVICE -> "Not forwarded: nobody has $label selected"
            SeenOutcome.PERSON_OFF -> "Not forwarded: everyone who has $label selected is switched off"
            SeenOutcome.BAD_NUMBER -> "Not forwarded: the saved number isn't valid"
            SeenOutcome.PAUSED -> "Not forwarded: sharing is paused"
            SeenOutcome.OUTSIDE_HOURS -> "Not forwarded: outside your hours"
            SeenOutcome.RATE_LIMITED -> "Not forwarded: limit reached"
            SeenOutcome.ALREADY_SENT -> "Not forwarded: already sent"
            SeenOutcome.STALE -> "Not forwarded: too old"
            SeenOutcome.NO_PERMISSION -> "Not forwarded: SMS permission missing"
            SeenOutcome.NOT_READ_AS_CODE -> "Not forwarded: not read as a login code"
            SeenOutcome.SEND_FAILED -> "Couldn't send: ${e.detail.ifEmpty { "the phone refused the text" }}"
        }
        return if (e.test) "Test: $text" else text
    }

    private fun nameOf(e: SeenCode, sources: List<ShareSource>): String? =
        e.serviceId?.let { id -> ShareSources.find(id, sources)?.label } ?: e.label.ifEmpty { null }

    /** The source shown in a row: its name, or "none". */
    fun serviceLabel(e: SeenCode, sources: List<ShareSource> = emptyList()): String = nameOf(e, sources) ?: "none"

    /**
     * The source "Share this sender's codes" would create (or reuse) for a row, or null when the row cannot offer it: a test, a sender
     * somebody already has chosen is fine (it opens the chooser), a phone number (header masked), a blocked sender or one that was not code-like.
     */
    fun shareable(e: SeenCode, sources: List<ShareSource> = emptyList()): ShareSource? {
        if (e.test || e.outcome != SeenOutcome.NO_ONE_FOR_SERVICE || e.header.startsWith("\u2022")) return null
        val key = ShareSources.key(e.header)
        if (key.isEmpty()) return null
        sources.firstOrNull { it.key == key }?.let { return it }
        return ShareSources.custom(e.header, e.label, sources)
    }

    /** The line under a person's card when nothing would be sent to them, or null. */
    fun noServicesWarning(contact: TrustedContact, sources: List<ShareSource> = emptyList()): String? =
        if (contact.services.none { ShareSources.exists(it, sources) }) "No senders chosen \u2014 nothing will be sent" else null
}

/**
 * Reads a login code from a service's own message whose wording the general classifier does not know ("Your Netflix code is
 * 123456"). Stricter than the classifier on purpose: it needs a code word AND exactly one plausible number, and is only used
 * after the sender header matched a service and the hard blocks passed.
 */
object ShareCodeReader {
    private val CODE_WORD = Regex("(?i)\\b(?:code|passcode|pin|otp|password|verif\\w*|sign-?\\s?in|log-?\\s?in)\\b")
    private val NUMBER = Regex("(?<![\\d.,])(\\d{4,8})(?![\\d]|[.,]\\d)")
    private val STATED = Regex("(?i)(?:\\bis|[:-])\\s*$")

    fun read(body: String): String? {
        if (!CODE_WORD.containsMatchIn(body)) return null
        val numbers = NUMBER.findAll(body).toList()
        if (numbers.isEmpty()) return null
        val distinct = numbers.map { it.groupValues[1] }.distinct()
        if (distinct.size == 1) return distinct.single()
        // Several numbers: only one that is said to be the code ("code is 123456", "code: 123456") will do.
        val stated = numbers.filter { m -> STATED.containsMatchIn(body.substring(maxOf(0, m.range.first - 6), m.range.first)) }.map { it.groupValues[1] }.distinct()
        return stated.singleOrNull()
    }
}
