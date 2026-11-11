package com.zyke.message.domain.diagnostics

import com.zyke.message.domain.conversation.SenderDisplay
import com.zyke.message.domain.feed.Feed
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.FeedItem
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.mms.MmsMessages
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.util.toBigDecimalOrNull
import com.zyke.message.domain.usecase.LoadProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What happened to one message between the phone's providers and the All tab. */
enum class Disposition(val label: String, val shownInAll: Boolean) {
    /** A row of its own in All. */
    ROW("shown as a row", true),

    /** Another message of an OTP group on one day: reached by opening the group's row. */
    IN_GROUP("inside an OTP group row", true),

    /** A message that repeats a bank debit: shown inside that debit's row ("+N related") and in its detail. */
    RELATED("inside a payment row (+N related)", true),

    PROMOTIONAL("hidden: promotional (Promos chip)", false),
    MUTED("hidden: muted sender (Promos chip)", false),
    ARCHIVED("hidden: archived (Archived chip)", false),

    /** The person merged it into another payment. */
    MERGED_BY_YOU("merged by you into another payment", false),

    /** A picture message and its chat notifications became one card (or a "Photo" placeholder was folded). */
    FOLDED_RICH("folded into a picture or chat card", false),

    /** Read from the phone but not in the list yet: older than the months kept, or still being read. */
    NOT_LOADED("not loaded: older than the months kept, or still being read", false),

    /** A picture message with neither a sender nor any text or picture; there is nothing to show. */
    MMS_UNUSABLE("dropped: picture message without sender or content", false),

    /** A chat or picture-message copy of an SMS (same sender and text within ten minutes), or a provider double insert: folded into the SMS that stays. */
    FOLDED_COPY("folded into the SMS it copies (same text)", false),

    /** A chat notification whose text is already an SMS (same text within ten minutes). */
    RCS_SEEN_AS_SMS("dropped: chat notification already read as an SMS", false),

    /** Nothing above explains it. Should never happen; if it does, this is the bug. */
    UNEXPLAINED("NOT EXPLAINED", false)
}

/** Where a provider row came from. */
enum class ProviderSource(val label: String) { SMS("sms"), MMS("mms"), RCS("chat") }

/** The id kinds the app uses: SMS rows are positive, picture messages far below, chat notifications in between. */
fun providerSourceOf(id: Long): ProviderSource = when {
    MmsMessages.isMmsId(id) -> ProviderSource.MMS
    id < 0 -> ProviderSource.RCS
    else -> ProviderSource.SMS
}

/** One of the newest rows of the providers: no text, only its length. */
data class ProviderRow(
    val id: Long, val date: Long, val sender: String, val length: Int, val source: ProviderSource, val type: Int = 1,
    /** The SIM (subscription id) the SMS row came in on, when the phone's provider says; null when unknown. A second SIM is one way a text is stored twice. */
    val subId: Int? = null
)

/** What the data layer found on the phone. All of it is ids and counts. */
class ProviderSnapshot(
    /** Every id the inbox scan lists: SMS inbox rows, picture messages in the MMS inbox, stored chat notifications. */
    val providerIds: LongArray,
    /** Every id the data source turns into a message (the same read the app makes), so what it drops can be told. */
    val materializedIds: LongArray,
    /** Rows in the SMS provider that are not in the inbox (sent, drafts, failed, queued...). */
    val smsOtherTypes: Int,
    /** The newest rows across all three sources, newest first. */
    val newest: List<ProviderRow>
)

class InboxAuditInput(
    val provider: ProviderSnapshot,
    /** What the pipeline hands every screen. */
    val messages: List<ClassifiedMessage>,
    /** Notification items folded into a picture message (dropped id to kept id). */
    val foldedInto: Map<Long, Long>,
    /** The person's merges (duplicate id to primary id). */
    val userMerges: Map<Long, Long>,
    val userState: UserMessageState,
    /** The filter the person has on right now. */
    val filter: MessageFilter,
    val progress: LoadProgress,
    val now: Instant,
    val zone: ZoneId,
    /** Messages folded into the SMS they copy (dropped id to the SMS kept); a part of [foldedInto]. */
    val copiedInto: Map<Long, Long> = emptyMap()
)

/** One message of a set of payment rows that look alike. No text; the sender is masked. */
data class AlikeMember(
    val date: Long,
    val source: ProviderSource,
    val maskedSender: String,
    val disposition: Disposition,
    /** Its text is word for word the first message's. */
    val sameTextAsFirst: Boolean,
    /** How many transaction reference numbers the text states (the numbers themselves are never shown). */
    val refCount: Int,
    /** Shares a reference number with the first message. */
    val sharesRefWithFirst: Boolean,
    /** What the app decided: "none", "certain repeat (layer)" or "question (layer)". */
    val decision: String
)

/** Payment rows with the same amount, account, direction and payee within a few minutes: the pairs the person calls duplicates. */
class AlikeGroup(val amount: String, val account: String, val incoming: Boolean, val members: List<AlikeMember>)

/** One of the newest rows and where it went. Sender masked, no text. */
data class NewestLine(
    val date: Long,
    val maskedSender: String,
    val length: Int,
    val source: ProviderSource,
    val type: Int,
    val disposition: Disposition,
    /** For a message inside another row: that row's date and masked sender. */
    val rowDate: Long? = null,
    val rowSender: String? = null,
    /** The SIM the SMS came in on, when the phone says. */
    val subId: Int? = null
)

class InboxAuditReport(
    val smsInbox: Int,
    val mmsInbox: Int,
    val chatCaptured: Int,
    val smsOtherTypes: Int,
    /** Rows in the All tab (a group of codes is one row; a payment with related messages is one row). */
    val rowsInAll: Int,
    val counts: Map<Disposition, Int>,
    /** Rows a filter chip, status, category or time filter the person has set hides from All right now. */
    val hiddenByFilters: Int,
    val filterDescription: String,
    val progress: LoadProgress,
    val newest: List<NewestLine>,
    /** Messages in the list that are not in the phone's providers (kept from memory while reading is paused, or hand-made entries). */
    val outsideProviders: Int,
    /** Payment rows that look alike (same amount, account, direction and payee within minutes), newest first. */
    val alike: List<AlikeGroup> = emptyList()
) {
    val providerTotal: Int get() = smsInbox + mmsInbox + chatCaptured
    fun count(d: Disposition): Int = counts[d] ?: 0

    /** Every message the phone holds is in exactly one disposition, so these add up to [providerTotal]. */
    val accountedFor: Int get() = counts.values.sum()

    /** Messages a person could think are missing: not reachable from All by scrolling or by opening a row. */
    val notReachableInAll: Int get() = Disposition.entries.filter { !it.shownInAll }.sumOf { count(it) }

    /** Plain text for a developer to read or share. No message text, senders masked. */
    fun toText(zone: ZoneId): String = buildString {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val exact = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
        appendLine("Zyke Message inbox audit")
        appendLine("No message text. Senders are masked.")
        appendLine()
        appendLine("On the phone")
        appendLine("  SMS inbox rows: $smsInbox")
        appendLine("  MMS inbox rows: $mmsInbox")
        appendLine("  Chat (RCS) items captured from notifications: $chatCaptured")
        appendLine("  SMS rows in other folders (sent, draft, failed...): $smsOtherTypes")
        appendLine("  Total that could appear: $providerTotal")
        appendLine()
        appendLine("In Zyke")
        appendLine("  Rows in All: $rowsInAll")
        appendLine("  Read so far: ${progress.monthsLoaded} of ${progress.monthsTotal} months, ${if (progress.isDone) "finished" else "still reading"}")
        appendLine("  Messages outside the phone's providers: $outsideProviders")
        appendLine()
        appendLine("Where every message went ($accountedFor of $providerTotal)")
        Disposition.entries.forEach { d -> if (count(d) > 0 || d == Disposition.UNEXPLAINED) appendLine("  ${d.label}: ${count(d)}") }
        appendLine("  hidden accounts: not a message filter, hides no message")
        appendLine("  (a payment's related messages and an OTP group's codes are in All, inside their row)")
        appendLine()
        appendLine("Filter used for this audit: $filterDescription")
        appendLine("  Rows it hides: $hiddenByFilters")
        appendLine()
        appendLine("Newest ${newest.size} rows on the phone")
        newest.forEach { line ->
            val where = if (line.rowDate != null) " in the row of ${line.rowSender} at ${stamp.format(Instant.ofEpochMilli(line.rowDate))}" else ""
            val sim = if (line.subId != null) " sim ${line.subId}" else ""
            appendLine("  ${exact.format(Instant.ofEpochMilli(line.date))}  ${line.source.label} (${idKind(line.source)})${if (line.source == ProviderSource.SMS) " type ${line.type}$sim" else ""}  ${line.maskedSender}  ${line.length} chars  ${line.disposition.label}$where")
        }
        appendLine()
        appendLine("Payment rows that look alike (same amount, account, direction and payee within ${ALIKE_WITHIN_MS / 60_000} minutes): ${alike.size}")
        appendLine("  No text and no reference numbers: only whether they match.")
        alike.forEach { g ->
            appendLine("  ${g.amount} ${if (g.incoming) "in" else "out"} account ${g.account}")
            g.members.forEach { m ->
                val refs = when {
                    m.refCount == 0 -> "no reference"
                    m === g.members.first() -> "${m.refCount} reference(s)"
                    m.sharesRefWithFirst -> "same reference as the first"
                    else -> "${m.refCount} reference(s), not the first's"
                }
                val text = if (m === g.members.first()) "" else if (m.sameTextAsFirst) ", identical text" else ", different text"
                appendLine("    ${exact.format(Instant.ofEpochMilli(m.date))}  ${m.source.label} (${idKind(m.source)})  ${m.maskedSender}  $refs$text  ${m.disposition.label}  decision: ${m.decision}")
            }
        }
    }
}

/** What kind of id a source has: an SMS row id is positive, a picture message's is far below zero, a chat item's is a small negative. */
fun idKind(source: ProviderSource): String = when (source) {
    ProviderSource.SMS -> "provider row id"
    ProviderSource.MMS -> "mms provider id"
    ProviderSource.RCS -> "notification-captured chat id"
}

/** Payment rows this close in time are listed together when they match. */
const val ALIKE_WITHIN_MS = 5 * 60 * 1000L

/**
 * Follows every message from the phone's providers to the All tab and says what became of it, so "not all messages in the
 * Messages app are seen in Zyke" can be answered with numbers. It runs only when asked (a developer tool): it builds the All feed
 * once more, with the filter cleared, and walks it; nothing here is on the normal path.
 *
 * The rows of All are not worked out a second time by hand: the feed is the real [FeedBuilder] output, so the audit cannot drift
 * from what the screen shows. Reasons for what is not in it are read from the message itself, in the order the feed applies them.
 */
object InboxAudit {
    /** Rows listed from the newest end of the providers. */
    const val NEWEST = 20

    /** Sets of alike payment rows listed. */
    const val ALIKE_LISTED = 30

    fun build(input: InboxAuditInput): InboxAuditReport {
        val provider = input.provider
        val byId = HashMap<Long, ClassifiedMessage>(input.messages.size * 2)
        for (m in input.messages) byId[m.id] = m

        val feed = FeedBuilder.build(input.messages, input.userState, MessageFilter.All, "", input.now, input.zone)
        val inFeed = HashMap<Long, Placed>()
        var rows = 0
        for (section in feed.sections) for (item in section.items) {
            rows++
            when (item) {
                is FeedItem.Single -> {
                    inFeed[item.message.id] = Placed(Disposition.ROW, null)
                    item.related.forEach { inFeed[it.id] = Placed(Disposition.RELATED, item.message) }
                }
                is FeedItem.Group -> {
                    val lead = item.messages.first()
                    inFeed[lead.id] = Placed(Disposition.ROW, null)
                    item.messages.drop(1).forEach { inFeed[it.id] = Placed(Disposition.IN_GROUP, lead) }
                }
            }
        }

        val materialized = HashSet<Long>(provider.materializedIds.size * 2).also { set -> provider.materializedIds.forEach { set += it } }

        fun dispose(id: Long): Placed {
            inFeed[id]?.let { return it }
            val message = byId[id]
            if (message != null) return Placed(hiddenReason(message, input.userState), null)
            return Placed(
                when {
                    id in input.copiedInto -> Disposition.FOLDED_COPY
                    id in input.foldedInto -> Disposition.FOLDED_RICH
                    id in input.userMerges -> Disposition.MERGED_BY_YOU
                    id !in materialized -> when (providerSourceOf(id)) {
                        ProviderSource.MMS -> Disposition.MMS_UNUSABLE
                        ProviderSource.RCS -> Disposition.RCS_SEEN_AS_SMS
                        ProviderSource.SMS -> Disposition.UNEXPLAINED
                    }
                    // Read from the phone, not in the list, nothing folded or merged it: it is waiting to be read, or older than the months kept.
                    else -> Disposition.NOT_LOADED
                },
                null
            )
        }

        val counts = LinkedHashMap<Disposition, Int>()
        var sms = 0
        var mms = 0
        var chat = 0
        for (id in provider.providerIds) {
            when (providerSourceOf(id)) {
                ProviderSource.SMS -> sms++
                ProviderSource.MMS -> mms++
                ProviderSource.RCS -> chat++
            }
            val d = dispose(id).disposition
            counts[d] = (counts[d] ?: 0) + 1
        }

        val providerIds = HashSet<Long>(provider.providerIds.size * 2).also { set -> provider.providerIds.forEach { set += it } }
        val outside = input.messages.count { it.id !in providerIds }

        val newest = provider.newest.take(NEWEST).map { row ->
            val placed = dispose(row.id)
            NewestLine(
                date = row.date, maskedSender = mask(row.sender), length = row.length, source = row.source, type = row.type,
                disposition = placed.disposition,
                rowDate = placed.row?.message?.date, rowSender = placed.row?.let { mask(it.message.sender) },
                subId = row.subId
            )
        }

        val hidden = if (input.filter.isActive) {
            FeedBuilder.build(input.messages, input.userState, input.filter, "", input.now, input.zone).hiddenByFilters
        } else {
            0
        }

        return InboxAuditReport(
            smsInbox = sms, mmsInbox = mms, chatCaptured = chat, smsOtherTypes = provider.smsOtherTypes,
            rowsInAll = rows, counts = counts, hiddenByFilters = hidden, filterDescription = describe(input.filter),
            progress = input.progress, newest = newest, outsideProviders = outside,
            alike = alike(input.messages) { dispose(it).disposition }
        )
    }

    /** Payment messages that agree on amount, account, direction and payee and sit within [ALIKE_WITHIN_MS] of each other, newest sets first. */
    private fun alike(messages: List<ClassifiedMessage>, disposition: (Long) -> Disposition): List<AlikeGroup> {
        class Tx(val m: ClassifiedMessage, val tx: MessageContent.Transaction)
        val byKey = HashMap<String, MutableList<Tx>>()
        for (m in messages) {
            val tx = m.content as? MessageContent.Transaction ?: continue
            if (m.isPromotional) continue
            val account = tx.accountNumber.filter { it.isDigit() }.takeLast(4)
            val amount = tx.amount.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: continue
            val payee = tx.payee.lowercase().filter { it.isLetterOrDigit() }
            byKey.getOrPut("$amount|$account|${tx.isReceived}|$payee") { ArrayList() } += Tx(m, tx)
        }
        val groups = ArrayList<AlikeGroup>()
        for (list in byKey.values) {
            if (list.size < 2) continue
            val ordered = list.sortedWith(compareBy<Tx> { it.m.message.date }.thenBy { it.m.id })
            var run = ArrayList<Tx>()
            fun flush() {
                if (run.size >= 2) {
                    val first = run.first()
                    val firstRefs = SamePayment.transactionRefs(first.m.message.body)
                    val members = run.map { t ->
                        val refs = SamePayment.transactionRefs(t.m.message.body)
                        val link = t.m.samePayment
                        AlikeMember(
                            date = t.m.message.date, source = providerSourceOf(t.m.id), maskedSender = mask(t.m.message.sender),
                            disposition = disposition(t.m.id),
                            sameTextAsFirst = t.m.message.body == first.m.message.body,
                            refCount = refs.size, sharesRefWithFirst = refs.any { it in firstRefs },
                            decision = when {
                                link == null -> "none"
                                link.kind == com.zyke.message.domain.ledger.SamePaymentKind.CERTAIN -> "certain repeat (${link.layer})"
                                else -> "question (${link.layer})"
                            }
                        )
                    }
                    val amount = first.tx.amount.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: first.tx.amount
                    groups += AlikeGroup(amount, first.tx.accountNumber.filter { it.isDigit() }.takeLast(4).ifEmpty { "?" }, first.tx.isReceived, members)
                }
                run = ArrayList()
            }
            for (t in ordered) {
                if (run.isNotEmpty() && t.m.message.date - run.last().m.message.date > ALIKE_WITHIN_MS) flush()
                run += t
            }
            flush()
        }
        return groups.sortedByDescending { it.members.last().date }.take(ALIKE_LISTED)
    }

    private class Placed(val disposition: Disposition, val row: ClassifiedMessage?)

    /** The reason a message in the list is not in All, in the order the feed applies them. */
    internal fun hiddenReason(message: ClassifiedMessage, userState: UserMessageState): Disposition = when {
        message.id in userState.archivedIds -> Disposition.ARCHIVED
        message.isMuted -> Disposition.MUTED
        message.isPromotional -> Disposition.PROMOTIONAL
        else -> Disposition.UNEXPLAINED
    }

    private fun describe(filter: MessageFilter): String {
        if (!filter.isActive) return "none (All)"
        return listOfNotNull(
            filter.smart.label.takeIf { filter.smart != com.zyke.message.domain.feed.SmartFilter.ALL },
            filter.category?.displayString,
            filter.status.label.takeIf { filter.status != com.zyke.message.domain.feed.StatusFilter.ANY },
            filter.window.label.takeIf { filter.window != com.zyke.message.domain.feed.TimeWindow.ANY }
        ).joinToString(", ")
    }

    /**
     * A sender that tells nothing about a person: a phone number keeps its first three characters and last two digits, a registered
     * header (a business) keeps its route, the first two letters of its name and its category, anything else (a contact's name)
     * keeps its first letter.
     */
    fun mask(sender: String): String {
        val s = sender.trim()
        if (s.isEmpty()) return "(none)"
        SenderDisplay.parseHeader(s)?.let { h ->
            return listOfNotNull(h.route, h.name.take(2) + "…", h.category?.toString()).joinToString("-")
        }
        val digits = s.count { it.isDigit() }
        if (digits >= 6 && digits * 2 >= s.length) {
            return s.take(3) + "•".repeat((s.length - 5).coerceAtLeast(1)) + s.takeLast(2)
        }
        return s.take(1) + "•".repeat((s.length - 1).coerceIn(1, 6))
    }
}
