package com.zyke.message.domain.ledger

import com.zyke.message.domain.dashboard.CardBillsBuilder
import com.zyke.message.domain.home.BillPayments
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** A debit from one of the person's accounts and a credit of the same amount to another, close in time: possibly one transfer. */
data class TransferCandidate(val debit: ClassifiedMessage, val credit: ClassifiedMessage) {
    val key: String get() = "${debit.id}:${credit.id}"
    val amount: BigDecimal get() = (debit.content as MessageContent.Transaction).amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

/**
 * Two messages that look like one payment: the same amount and direction, close in time, naming the same account or
 * payee. [first] is the earlier one and stands for the payment if the person merges them.
 */
data class DuplicateCandidate(val first: ClassifiedMessage, val second: ClassifiedMessage) {
    val key: String get() = "dup:${first.id}:${second.id}"
    val amount: BigDecimal get() = (first.content as MessageContent.Transaction).amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

/** A card bill whose due date has passed with no payment found in the messages. */
data class OverdueBill(
    val message: ClassifiedMessage,
    val label: String,
    val amount: BigDecimal,
    val dueDate: LocalDate,
    /** What "mark as paid" stores for it (see UpcomingItem.markedPaidKey). */
    val markedPaidKey: String,
    /** The key the same bill has in the attention strip. */
    val attentionKey: String
)

/** What the app is not sure of and would like the person to look at. */
data class ReviewQueue(
    val transfers: List<TransferCandidate>,
    /** Messages that mention money but were not understood. */
    val unclear: List<ClassifiedMessage>,
    /** Larger payments filed under nothing in particular. */
    val uncategorised: List<ClassifiedMessage>,
    /** Pairs of messages that may be one payment. */
    val duplicates: List<DuplicateCandidate> = emptyList(),
    /** Card bills past their due date with no payment found. */
    val bills: List<OverdueBill> = emptyList(),
    /** Debits far above what the person usually pays. */
    val unusual: List<ClassifiedMessage> = emptyList()
) {
    val count: Int get() = transfers.size + unclear.size + uncategorised.size + duplicates.size + bills.size + unusual.size
    val isEmpty: Boolean get() = count == 0
}

object ReviewQueues {
    private const val MAX_PER_KIND = 20
    private val BIG = BigDecimal(1_000)
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Two messages this close in time can be one payment. */
    const val DUPLICATE_WITHIN_MS = 15L * 60 * 1000

    /** A bill overdue for longer than this is left alone: too old to be a useful question. */
    const val OVERDUE_BILL_MAX_DAYS = 60L

    /** A debit this many times the median is unusual. */
    const val UNUSUAL_FACTOR = UnusualSpend.FACTOR

    /** The median is taken over debits in this many days. */
    const val UNUSUAL_HISTORY_DAYS = UnusualSpend.HISTORY_DAYS

    /** Fewer debits than this and there is no telling what is usual. */
    const val UNUSUAL_MIN_DEBITS = UnusualSpend.MIN_DEBITS

    /** Only debits this recent are asked about. */
    const val UNUSUAL_ASK_DAYS = 30L

    /**
     * @param now the current time, for overdue bills and what counts as recent
     * @param dismissed keys the person marked as paid or dismissed (the attention strip's dismissals)
     */
    fun build(
        messages: List<ClassifiedMessage>,
        asides: SetAsides,
        zone: ZoneId,
        now: Instant = Instant.now(),
        dismissed: Set<String> = emptySet()
    ): ReviewQueue {
        val live = messages.filter { !it.isPromotional && !it.isMuted && it.setAside == null }

        val txs = live.filter { it.content is MessageContent.Transaction && !ManualEntries.isManual(it) }
            .filter { (it.content as MessageContent.Transaction).let { tx -> !tx.isCardPayment && last4(tx).isNotEmpty() } }
        val debits = txs.filter { !(it.content as MessageContent.Transaction).isReceived }
        val credits = txs.filter { (it.content as MessageContent.Transaction).isReceived }

        val used = HashSet<Long>()
        val transfers = debits.sortedByDescending { it.message.date }.mapNotNull { debit ->
            val d = debit.content as MessageContent.Transaction
            val match = credits.firstOrNull { credit ->
                val c = credit.content as MessageContent.Transaction
                credit.id !in used &&
                    c.amount.toBigDecimalOrNull()?.compareTo(d.amount.toBigDecimalOrNull() ?: return@firstOrNull false) == 0 &&
                    last4(c) != last4(d) &&
                    abs(credit.message.date - debit.message.date) <= DAY_MS &&
                    "${debit.id}:${credit.id}" !in asides.dismissed
            } ?: return@mapNotNull null
            used += match.id
            TransferCandidate(debit, match)
        }.take(MAX_PER_KIND)

        val unclear = live.filter { it.content is MessageContent.Generic && com.zyke.message.domain.classification.GenericTransactionParser.mentionsMoney(it.message.body) }
            .take(MAX_PER_KIND)

        val uncategorised = txs.filter {
            val tx = it.content as MessageContent.Transaction
            it.category == MessageCategory.OTHERS && !it.categoryOverridden && !tx.isReceived &&
                (tx.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO) >= BIG
        }.take(MAX_PER_KIND)

        val duplicates = duplicates(live, asides)
        val bills = overdueBills(live, dismissed, now, zone)
        val unusual = unusual(txs, asides, now).filter { u -> uncategorised.none { it.id == u.id } }

        return ReviewQueue(transfers, unclear, uncategorised, duplicates, bills, unusual)
    }

    private fun duplicates(live: List<ClassifiedMessage>, asides: SetAsides): List<DuplicateCandidate> {
        val txs = live.filter { it.content is MessageContent.Transaction && !ManualEntries.isManual(it) }.sortedBy { it.message.date }
        val used = HashSet<Long>()
        val found = ArrayList<DuplicateCandidate>()
        for (i in txs.indices) {
            val a = txs[i]
            if (a.id in used) continue
            val ta = a.content as MessageContent.Transaction
            val amount = ta.amount.toBigDecimalOrNull() ?: continue
            for (j in i + 1 until txs.size) {
                val b = txs[j]
                if (b.message.date - a.message.date > DUPLICATE_WITHIN_MS) break
                val tb = b.content as MessageContent.Transaction
                val candidate = DuplicateCandidate(a, b)
                if (b.id in used || b.id == a.id || tb.isReceived != ta.isReceived || candidate.key in asides.dismissed ||
                    tb.amount.toBigDecimalOrNull()?.compareTo(amount) != 0 || !sameHint(ta, tb)
                ) continue
                used += a.id
                used += b.id
                found += candidate
                break
            }
        }
        return found.sortedByDescending { it.second.message.date }.take(MAX_PER_KIND)
    }

    private fun sameHint(a: MessageContent.Transaction, b: MessageContent.Transaction): Boolean {
        val accountA = last4(a)
        val samePayee = a.payee.isNotBlank() && a.payee.trim().equals(b.payee.trim(), ignoreCase = true)
        return (accountA.isNotEmpty() && accountA == last4(b)) || samePayee
    }

    private fun overdueBills(live: List<ClassifiedMessage>, dismissed: Set<String>, now: Instant, zone: ZoneId): List<OverdueBill> {
        val today = LocalDate.ofInstant(now, zone)
        val byId = live.associateBy { it.id }
        return CardBillsBuilder.build(live, today).mapNotNull { card ->
            val due = card.latest.dueDate ?: return@mapNotNull null
            if (!due.isBefore(today) || due.isBefore(today.minusDays(OVERDUE_BILL_MAX_DAYS))) return@mapNotNull null
            val message = byId[card.latest.messageId] ?: return@mapNotNull null
            val bill = message.content as? MessageContent.CardBill ?: return@mapNotNull null
            if (BillPayments.find(message, bill, live, zone) != null) return@mapNotNull null
            // The same keys "mark as paid" stores for this bill on Home.
            val markedPaid = "paid-bill-${card.key}-$due"
            val attention = "bill-${bill.bankName.name}-${bill.accountNumber}-$due"
            if (markedPaid in dismissed || attention in dismissed) return@mapNotNull null
            OverdueBill(message, card.label, card.latest.amount, due, markedPaid, attention)
        }.sortedBy { it.dueDate }.take(MAX_PER_KIND)
    }

    private fun unusual(txs: List<ClassifiedMessage>, asides: SetAsides, now: Instant): List<ClassifiedMessage> {
        val nowMs = now.toEpochMilli()
        val recent = txs.mapNotNull { m ->
            val tx = m.content as MessageContent.Transaction
            if (tx.isReceived) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            if (m.message.date < nowMs - UNUSUAL_HISTORY_DAYS * DAY_MS || m.message.date > nowMs) return@mapNotNull null
            m to amount
        }
        val threshold = UnusualSpend.threshold(recent.map { it.second }) ?: return emptyList()
        return recent.filter { (m, amount) ->
            amount >= threshold && m.message.date >= nowMs - UNUSUAL_ASK_DAYS * DAY_MS &&
                !m.categoryOverridden && !m.categoryOwn && unusualKey(m) !in asides.dismissed
        }.map { it.first }.sortedByDescending { it.message.date }.take(MAX_PER_KIND)
    }

    /** What "looks right" stores for a large debit. */
    fun unusualKey(message: ClassifiedMessage): String = "big:${message.id}"

    private fun last4(tx: MessageContent.Transaction) = tx.accountNumber.filter { it.isDigit() }.takeLast(4)

    @Suppress("unused")
    private fun day(message: ClassifiedMessage, zone: ZoneId): LocalDate = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
}
