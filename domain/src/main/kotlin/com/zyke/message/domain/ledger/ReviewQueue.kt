package com.zyke.message.domain.ledger

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

/** What the app is not sure of and would like the person to look at. */
data class ReviewQueue(
    val transfers: List<TransferCandidate>,
    /** Messages that mention money but were not understood. */
    val unclear: List<ClassifiedMessage>,
    /** Larger payments filed under nothing in particular. */
    val uncategorised: List<ClassifiedMessage>
) {
    val count: Int get() = transfers.size + unclear.size + uncategorised.size
    val isEmpty: Boolean get() = count == 0
}

object ReviewQueues {
    private const val MAX_PER_KIND = 20
    private val BIG = BigDecimal(1_000)
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun build(messages: List<ClassifiedMessage>, asides: SetAsides, zone: ZoneId): ReviewQueue {
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

        return ReviewQueue(transfers, unclear, uncategorised)
    }

    private fun last4(tx: MessageContent.Transaction) = tx.accountNumber.filter { it.isDigit() }.takeLast(4)

    @Suppress("unused")
    private fun day(message: ClassifiedMessage, zone: ZoneId): LocalDate = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
}
