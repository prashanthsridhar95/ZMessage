package com.zyke.message.domain.split

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One person who owes part of a shared payment. */
data class SplitPerson(
    val name: String,
    val share: BigDecimal,
    /** Marked paid by hand. A matching credit also settles it; see [SplitInsights]. */
    val settledByHand: Boolean = false
)

/** A payment the person made for a group and wants to get a part of back. */
data class Split(
    val id: String,
    /** The payment's message; the splits shows its title and total. */
    val messageId: Long,
    val title: String,
    val total: BigDecimal,
    val people: List<SplitPerson>
)

/** A person's share, and whether it has come back. */
data class SplitShare(val split: Split, val person: SplitPerson, val settled: Boolean, val settledOn: LocalDate?)

object SplitInsights {
    private val TOLERANCE = BigDecimal("0.02")

    /**
     * Each person's share with whether it has been paid back: marked by hand, or a credit that arrived after the
     * payment from someone whose name matches and for about the share.
     */
    fun build(splits: List<Split>, messages: List<ClassifiedMessage>, zone: ZoneId): List<SplitShare> {
        val byId = messages.associateBy { it.id }
        val credits = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (!tx.isReceived || tx.isCardPayment) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            Credit(message.id, message.message.date, amount, "${tx.payee} ${message.merchant.name}".lowercase())
        }
        val used = HashSet<Long>()

        return splits.flatMap { split ->
            val paidAt = byId[split.messageId]?.message?.date ?: 0L
            split.people.map { person ->
                val words = person.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
                val match = if (person.settledByHand) {
                    null
                } else {
                    credits.firstOrNull { credit ->
                        credit.id !in used && credit.date >= paidAt && words.any { it in credit.who } &&
                            (credit.amount - person.share).abs() <= person.share.multiply(TOLERANCE)
                    }?.also { used += it.id }
                }
                SplitShare(
                    split, person,
                    settled = person.settledByHand || match != null,
                    settledOn = match?.let { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
                )
            }
        }
    }

    /** What is still owed in total. */
    fun outstanding(shares: List<SplitShare>): BigDecimal =
        shares.filter { !it.settled }.fold(BigDecimal.ZERO) { sum, s -> sum + s.person.share }

    private class Credit(val id: Long, val date: Long, val amount: BigDecimal, val who: String)
}
