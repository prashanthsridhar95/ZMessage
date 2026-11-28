package com.zyke.message.domain.triage

import com.zyke.message.domain.conversation.SenderDisplay
import com.zyke.message.domain.feed.OtpPolicy
import com.zyke.message.domain.home.BillPayments
import com.zyke.message.domain.insights.DigestFormat
import com.zyke.message.domain.insights.RefundStage
import com.zyke.message.domain.insights.RefundTracker
import com.zyke.message.domain.ledger.PaymentGroups
import com.zyke.message.domain.life.Journeys
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.life.TravelMode
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CourierStatus
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.util.toBigDecimalOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home's deck: the messages that deserve a look, sorted into a few small stacks of cards.
 *
 * Everything is derived from what the classifier already found (no new parsing) and nothing is stored here except the person's
 * order and modes ([TriageSettings]). A card is an UNREAD, UNARCHIVED message of the last [HORIZON_DAYS] days; reading or archiving
 * it (the person's own marks, set by "Got it" and "Sweep it" or anywhere else in the app) takes it off the deck.
 *
 * Which stack a message belongs to, first match wins:
 *  - a repeat or follower of a payment: no card of its own (the payment's card stands for it);
 *  - a one-time code: only an EXPIRED one (a fresh one is Home's code card); stack Codes;
 *  - a muted sender's message: Offers when it is a plain message, otherwise none (the person muted it);
 *  - a promotional plain message: Offers;
 *  - Transaction: Payments; CardBill, ScheduledPayment, DUES_REMINDER / AUTOPAY_FAILED / PAYMENT_DECLINED / autopay / payment request
 *    notices: Bills & dues; Life (orders, trips, appointments, renewals, warranties) and Courier: Orders & travel; refund notices: Refunds;
 *    FASTag and every other notice: Account alerts; gateway "payment confirmed" notices: none (repeats of a debit);
 *    everything else (a plain message, data use, loyalty): Everything else.
 *
 * SAFETY RULE for auto-clear: a stack whose mode is AUTO_CLEAR is cleared silently only if it is Codes (expired codes) or Offers
 * (plain promotional or muted messages). [TriageSettings.modeOf] never returns AUTO_CLEAR for any other stack, and [autoClearable]
 * checks the message itself again, so a transaction, bill, refund or balance alert can never be cleared without being seen.
 */
object TriageStacks {
    const val HORIZON_DAYS = 30L
    private const val BILL_MAX_AGE_DAYS = 60L
    private const val BILL_OVERDUE_SHOWN_DAYS = 14L
    private const val GENERIC_SUMMARY_CHARS = 120
    private const val HEADLINE_CHARS = 60
    private const val DUE_SOON_DAYS = 3L

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val SPACES = Regex("\\s+")

    /** The only cards that may ever be cleared without a look: expired codes and plain marketing. */
    fun autoClearable(m: ClassifiedMessage, now: Instant): Boolean = when (val c = m.content) {
        is MessageContent.Otp -> OtpPolicy.isExpired(m, now)
        MessageContent.Generic -> m.isPromotional || m.isMuted
        else -> false
    }

    /**
     * @param messages the inbox (any order); archived and read ones are counted, not shown
     * @param restored messages brought back from Auto cleared: they wait under Quiet and are not cleared again
     */
    fun build(
        messages: List<ClassifiedMessage>,
        userState: UserMessageState,
        settings: TriageSettings,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        restored: Set<Long> = emptySet()
    ): TriageDeck {
        val today = LocalDate.ofInstant(now, zone)
        val cutoff = now.toEpochMilli() - HORIZON_DAYS * DAY_MS
        val sorted = messages.sortedByDescending { it.message.date }
        val groups = PaymentGroups.of(sorted) { it.id in userState.archivedIds || it.id in userState.pinnedIds }
        val newestBillPerCard = HashSet<Long>()
        run {
            val seen = HashSet<Pair<Any, String>>()
            for (m in sorted) {
                val bill = m.content as? MessageContent.CardBill ?: continue
                if (seen.add(bill.bankName to bill.accountNumber)) newestBillPerCard += m.id
            }
        }

        val perStack = HashMap<String, ArrayList<TriageCard>>()
        var read = 0
        for (m in sorted) {
            val old = m.message.date < cutoff
            val isBill = m.content is MessageContent.CardBill
            if (old && !isBill) continue
            if (groups.isFollower(m.id)) continue
            val stackId = stackFor(m, now, today) ?: continue
            if (m.content is MessageContent.CardBill) {
                if (m.id !in newestBillPerCard || !billStillRelevant(m, m.content, now, today)) continue
                if (BillPayments.find(m, m.content, sorted, zone) != null) { read++; continue }
            }
            val followers = groups.followersOf(m.id)
            val unread = isUnread(m, userState) || followers.any { isUnread(it, userState) }
            if (m.id in userState.archivedIds || !unread) { read++; continue }
            val card = card(m, stackId, followers.map { it.id }, today, now)
            perStack.getOrPut(stackId) { ArrayList() } += card
        }

        val stacks = ArrayList<TriageStack>()
        var toGo = 0
        var quiet = 0
        val cleared = ArrayList<TriageCard>()
        val byId = sorted.associateBy { it.id }
        for (id in settings.orderedIds()) {
            val spec = TriageStackSpec.byId(id) ?: continue
            val mode = settings.modeOf(id)
            val cards = perStack[id].orEmpty()
            val stack = if (mode == TriageMode.AUTO_CLEAR && spec.canAutoClear) {
                val (clear, keep) = cards.partition { c -> c.messageId !in restored && byId[c.messageId]?.let { autoClearable(it, now) } == true }
                cleared += clear
                TriageStack(id, spec.title, mode, keep, clear)
            } else TriageStack(id, spec.title, if (mode == TriageMode.AUTO_CLEAR) TriageMode.KEEP else mode, cards)
            if (stack.mode == TriageMode.KEEP) toGo += stack.items.size else quiet += stack.items.size
            stacks += stack
        }
        cleared.sortByDescending { it.dateMillis }
        return TriageDeck(stacks, toGo, quiet, cleared, read)
    }

    private const val DAY_MS = 86_400_000L

    private fun isUnread(m: ClassifiedMessage, state: UserMessageState): Boolean = !m.message.isRead && m.id !in state.readIds

    private fun billStillRelevant(m: ClassifiedMessage, bill: MessageContent.CardBill, now: Instant, today: LocalDate): Boolean {
        val ageDays = (now.toEpochMilli() - m.message.date) / DAY_MS
        if (ageDays > BILL_MAX_AGE_DAYS) return false
        val due = bill.dueDate
        return if (due != null) !due.isBefore(today.minusDays(BILL_OVERDUE_SHOWN_DAYS)) else ageDays <= HORIZON_DAYS
    }

    /** The stack id a message belongs to, or null when it is not a card at all. */
    internal fun stackFor(m: ClassifiedMessage, now: Instant, today: LocalDate): String? {
        val c = m.content
        if (c is MessageContent.Otp) return if (OtpPolicy.isExpired(m, now)) TriageStackSpec.CODES else null
        if (m.isMuted) return if (c == MessageContent.Generic) TriageStackSpec.OFFERS else null
        if (c == MessageContent.Generic && m.isPromotional) return TriageStackSpec.OFFERS
        return when (c) {
            is MessageContent.Transaction -> TriageStackSpec.PAYMENTS
            is MessageContent.CardBill -> TriageStackSpec.BILLS
            is MessageContent.ScheduledPayment ->
                if (c.kind == ScheduledKind.UPCOMING_DEBIT && c.date != null && c.date.isBefore(today)) null else TriageStackSpec.BILLS
            is MessageContent.Life, is MessageContent.Courier -> TriageStackSpec.LIFE
            is MessageContent.FastTag -> TriageStackSpec.ALERTS
            is MessageContent.Notice -> when (c.kind) {
                NoticeKind.REFUND_PENDING -> TriageStackSpec.REFUNDS
                NoticeKind.PAYMENT_CONFIRMATION -> null
                NoticeKind.DUES_REMINDER, NoticeKind.AUTOPAY_FAILED, NoticeKind.PAYMENT_DECLINED, NoticeKind.PAYMENT_REQUEST,
                NoticeKind.MANDATE_MODIFIED, NoticeKind.MANDATE_DECLINED -> TriageStackSpec.BILLS
                else -> TriageStackSpec.ALERTS
            }
            is MessageContent.Otp -> null
            is MessageContent.DataUsage, is MessageContent.Loyalty, MessageContent.Generic -> TriageStackSpec.OTHER
        }
    }

    // ---- cards

    private fun rupees(raw: String?): String? = raw?.toBigDecimalOrNull()?.let { DigestFormat.rupees(it) }

    private fun day(d: LocalDate): String = DAY.format(d)

    private fun bankLabel(bank: com.zyke.message.domain.model.BankName, isCard: Boolean, last4: String): String? {
        val name = bank.displayString.takeIf { it.isNotBlank() } ?: return null
        val kind = if (isCard) "$name Card" else name
        return if (last4.isNotBlank()) "$kind ••$last4" else kind
    }

    private fun clip(text: String, max: Int): String {
        val flat = text.replace(SPACES, " ").trim()
        if (flat.length <= max) return flat
        val cut = flat.substring(0, max)
        val space = cut.lastIndexOf(' ')
        return (if (space > max / 2) cut.substring(0, space) else cut).trimEnd(',', ';', ':', '-', ' ') + "…"
    }

    private fun firstSentence(text: String): String {
        val flat = text.replace(SPACES, " ").trim()
        val end = flat.indexOfAny(charArrayOf('.', '!', '?', '\n')).let { if (it in 12..HEADLINE_CHARS) it else -1 }
        return if (end > 0) flat.substring(0, end) else clip(flat, HEADLINE_CHARS)
    }

    private fun lines(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }.take(2).joinToString("\n")

    private fun card(m: ClassifiedMessage, stackId: String, followerIds: List<Long>, today: LocalDate, now: Instant): TriageCard {
        val label = SenderDisplay.of(m.message.sender, m.merchant.name.takeIf { m.merchant.renamed }, m.merchant.name.takeIf { it.isNotBlank() })
        var title = label.name
        var verified = label.verified
        var tone = TriageTone.NEUTRAL
        val tags = ArrayList<TriageTag>()
        val headline: String
        val summary: String
        when (val c = m.content) {
            is MessageContent.Transaction -> {
                val amount = rupees(c.amount) ?: c.amount
                val payee = c.payee.trim()
                val who = payee.takeIf { it.isNotEmpty() }
                headline = when {
                    c.isCardPayment -> "Card bill payment of $amount received"
                    c.isReceived && c.isCard -> "$amount refunded" + (who?.let { " by $it" } ?: "")
                    c.isReceived -> "$amount received" + (who?.let { " from $it" } ?: "")
                    c.isCard -> "$amount spent" + (who?.let { " at $it" } ?: "")
                    else -> "$amount sent" + (who?.let { " to $it" } ?: "")
                }
                // A person or shop's name is the title; the bank header's check mark does not belong to a payee.
                if (who != null) {
                    val name = m.merchant.name.ifBlank { who }
                    title = if (m.merchant.renamed || m.merchant.category == MessageCategory.UPI_PAYMENT) name else SenderDisplay.titleCase(name)
                    verified = false
                }
                if (c.isReceived || c.isCardPayment) tone = TriageTone.GOOD
                val instrument = bankLabel(c.bankName, c.isCard, c.accountNumber) ?: (label.name + c.accountNumber.takeIf { it.isNotBlank() }?.let { " ••$it" }.orEmpty())
                val category = m.category.displayString.takeIf { m.category != MessageCategory.OTHERS && m.category != MessageCategory.UPI_PAYMENT }
                summary = lines(instrument, category)
                if (c.accountNumber.isNotBlank()) tags += TriageTag("••${c.accountNumber}")
                if (m.cardBill != null) tags += TriageTag("Matched to bill", TriageTone.GOOD)
                if (c.isCardPayment) tags += TriageTag("Bill paid", TriageTone.GOOD)
            }
            is MessageContent.CardBill -> {
                val card = bankLabel(c.bankName, c.bankName.isCard, c.accountNumber) ?: title
                val amount = rupees(c.amount) ?: c.amount
                val due = c.dueDate
                headline = "$card bill $amount" + (due?.let { " due ${day(it)}" } ?: "")
                val minimum = rupees(c.minimumDue)
                summary = lines(minimum?.let { "Minimum due $it" }, due?.let { dueLine(it, today) })
                if (due != null) {
                    val left = due.toEpochDay() - today.toEpochDay()
                    when {
                        left < 0 -> { tags += TriageTag("Overdue", TriageTone.DANGER); tone = TriageTone.DANGER }
                        left <= DUE_SOON_DAYS -> { tags += TriageTag("Due ${day(due)}", TriageTone.WARNING); tone = TriageTone.WARNING }
                        else -> tags += TriageTag("Due ${day(due)}")
                    }
                }
                if (c.accountNumber.isNotBlank()) tags += TriageTag("••${c.accountNumber}")
                minimum?.let { tags += TriageTag("Min $it") }
            }
            is MessageContent.ScheduledPayment -> {
                val amount = rupees(c.amount)
                val payee = c.payee.trim().takeIf { it.isNotEmpty() }
                headline = when (c.kind) {
                    ScheduledKind.UPCOMING_DEBIT -> (amount ?: "A payment") + " will be auto-debited" + (payee?.let { " for $it" } ?: "")
                    ScheduledKind.MANDATE_SET -> "Autopay set up" + (payee?.let { " for $it" } ?: "")
                    ScheduledKind.MANDATE_CANCELLED -> "Autopay cancelled" + (payee?.let { " for $it" } ?: "")
                    ScheduledKind.MANDATE_REQUEST -> "Autopay request" + (payee?.let { " from $it" } ?: "")
                }
                summary = lines(bankLabel(c.bankName, c.isCard, c.accountNumber), c.date?.let { "On ${day(it)}" } ?: amount?.let { "Amount $it" })
                c.date?.let {
                    val left = it.toEpochDay() - today.toEpochDay()
                    tags += TriageTag(day(it), if (left in 0..DUE_SOON_DAYS) TriageTone.WARNING else TriageTone.NEUTRAL)
                    if (left in 0..DUE_SOON_DAYS) tone = TriageTone.WARNING
                }
                if (c.kind == ScheduledKind.MANDATE_CANCELLED) tags += TriageTag("Cancelled")
            }
            is MessageContent.Life -> {
                headline = if (c.title.isNotBlank()) "${c.title}: ${c.status}" + (c.date?.takeIf { c.kind == LifeKind.RENEWAL }?.let { " on ${day(it)}" } ?: "") else c.status
                val route = if (c.kind == LifeKind.TRAVEL) Journeys.of(m.message.body, m.message.sender).let { j -> j.route?.let { r -> "${j.mode.label}: $r" } ?: j.mode.takeIf { it != TravelMode.OTHER }?.label } else null
                val whenLine = c.date?.let { d -> day(d) + (c.time?.let { t -> ", " + t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) } ?: "") }
                summary = lines(route ?: c.kind.label, whenLine ?: rupees(c.amount)?.let { "Order value $it" })
                c.date?.let {
                    val left = it.toEpochDay() - today.toEpochDay()
                    tags += TriageTag(day(it), if (left in 0..DUE_SOON_DAYS && c.kind == LifeKind.RENEWAL) TriageTone.WARNING else TriageTone.NEUTRAL)
                    if (c.kind == LifeKind.RENEWAL && left in 0..DUE_SOON_DAYS) tone = TriageTone.WARNING
                }
                c.reference?.takeIf { it.isNotBlank() }?.let { tags += TriageTag("Ref ${it.take(12)}") }
                if (c.isDone) tags += TriageTag("Done", TriageTone.GOOD)
            }
            is MessageContent.Courier -> {
                headline = when (c.status) {
                    CourierStatus.ARRIVING_TODAY -> "Parcel arriving today"
                    CourierStatus.DELIVERED -> "Parcel delivered"
                    CourierStatus.UPDATE -> "Parcel update"
                }
                summary = lines(label.name)
                if (c.status == CourierStatus.DELIVERED) { tags += TriageTag("Delivered", TriageTone.GOOD); tone = TriageTone.GOOD }
            }
            is MessageContent.FastTag -> {
                val e = c.event
                headline = when (e) {
                    is FastTagEvent.RechargeNeeded -> "FASTag ${e.vehicleNumber} needs a recharge"
                    is FastTagEvent.TollPaid -> (rupees(e.tollAmount) ?: e.tollAmount).let { "Toll $it paid" } + (e.tollName.takeIf { it.isNotBlank() }?.let { " at $it" } ?: "")
                    is FastTagEvent.ToppedUp -> "FASTag topped up with ${rupees(e.amount) ?: e.amount}"
                    is FastTagEvent.Activated -> "FASTag ${e.vehicleNumber} activated"
                }
                val balance = when (e) {
                    is FastTagEvent.TollPaid -> rupees(e.balance)
                    is FastTagEvent.ToppedUp -> rupees(e.balance)
                    else -> null
                }
                summary = lines(e.vehicleNumber, balance?.let { "Balance $it" })
                tags += TriageTag(e.vehicleNumber)
                if (e is FastTagEvent.RechargeNeeded) { tags += TriageTag("Low balance", TriageTone.DANGER); tone = TriageTone.DANGER }
            }
            is MessageContent.Notice -> {
                val amount = rupees(c.amount)
                if (c.kind == NoticeKind.REFUND_PENDING) {
                    val stage = RefundTracker.stageOf(m.message.body)
                    val word = if (stage == RefundStage.PROCESSED) "processed" else "initiated"
                    headline = "Refund" + (amount?.let { " of $it" } ?: "") + " $word"
                    summary = lines(title.takeIf { it.isNotBlank() }, if (stage == RefundStage.PROCESSED) "Should reach you soon" else "Expected in 5–7 days")
                    tags += TriageTag("Refund $word", if (stage == RefundStage.PROCESSED) TriageTone.GOOD else TriageTone.NEUTRAL)
                    tone = if (stage == RefundStage.PROCESSED) TriageTone.GOOD else TriageTone.NEUTRAL
                } else {
                    headline = c.kind.label + (amount?.let { " · $it" } ?: "")
                    summary = lines(c.detail?.let { clip(it, GENERIC_SUMMARY_CHARS) } ?: clip(m.message.body, GENERIC_SUMMARY_CHARS))
                    if (c.kind == NoticeKind.AUTOPAY_FAILED || c.kind == NoticeKind.PAYMENT_DECLINED || c.kind == NoticeKind.MANDATE_DECLINED) {
                        tags += TriageTag(c.kind.label, TriageTone.DANGER); tone = TriageTone.DANGER
                    } else if (c.kind == NoticeKind.DUES_REMINDER) { tags += TriageTag("Reminder", TriageTone.WARNING); tone = TriageTone.WARNING }
                }
            }
            is MessageContent.Otp -> {
                // Only expired codes are cards, and the code itself is never shown.
                headline = "Code from ${label.name} has expired"
                summary = lines("Codes last ${OtpPolicy.VALIDITY.toMinutes()} minutes", c.paymentAmount?.let { rupees(it) }?.let { "For a payment of $it" })
                tags += TriageTag("Expired")
            }
            is MessageContent.DataUsage -> {
                headline = if (c.hasPercent) "${c.percentUsed}% of your data is used" else if (c.isExhausted) "Your data is finished" else "${c.left ?: "Little"} of your data is left"
                summary = lines(c.carrier.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' '))
                if (c.percentUsed >= 90 || c.isExhausted || !c.hasPercent) { tags += TriageTag("Almost used up", TriageTone.WARNING); tone = TriageTone.WARNING }
            }
            is MessageContent.Loyalty -> {
                headline = c.programme.ifBlank { label.name } + (if (c.isLoaded) ": points added" else ": points used")
                summary = lines(c.points.takeIf { it.isNotBlank() }?.let { "$it points" }, c.balance.takeIf { it.isNotBlank() }?.let { "Balance $it" })
            }
            MessageContent.Generic -> {
                val body = m.message.body
                val head = firstSentence(body)
                headline = head
                val full = clip(body, GENERIC_SUMMARY_CHARS)
                summary = if (full.length <= head.length + 1) "" else full
                if (m.isPromotional || m.isMuted) tags += TriageTag("Offer")
            }
        }
        return TriageCard(m.id, stackId, title, verified, m.message.date, headline, summary, tags.take(3), tone, followerIds)
    }

    private fun dueLine(due: LocalDate, today: LocalDate): String {
        val left = due.toEpochDay() - today.toEpochDay()
        return when {
            left < 0L -> "Overdue by ${-left} ${if (left == -1L) "day" else "days"}"
            left == 0L -> "Due today"
            left == 1L -> "Due tomorrow"
            else -> "Due in $left days"
        }
    }
}
