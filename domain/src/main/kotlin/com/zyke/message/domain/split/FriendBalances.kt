package com.zyke.message.domain.split

import com.zyke.message.domain.insights.PaceNudges
import com.zyke.message.domain.model.ClassifiedMessage
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything between the person and one friend, across all shared payments. */
data class FriendBalance(
    val name: String,
    /** What the friend owes the person, from payments the person made. */
    val owedToYou: BigDecimal,
    /** What the person owes the friend, from payments the friend made. */
    val youOwe: BigDecimal,
    /** The still-open shares, newest payment first. */
    val open: List<SplitShare>,
    val settledCount: Int,
    /** The latest payment or settlement involving them. */
    val lastActivity: LocalDate?
) {
    /** Positive when the friend owes the person, negative when the person owes the friend. */
    val net: BigDecimal get() = owedToYou - youOwe
    val isSettled: Boolean get() = open.isEmpty()

    /** "Ravi owes you ₹1,350 across 4 payments". */
    val suggestion: String
        get() {
            val count = open.size
            val payments = if (count == 1) "1 payment" else "$count payments"
            return when {
                open.isEmpty() -> "All settled with $name"
                net.signum() > 0 -> "$name owes you ${PaceNudges.money(net)} across $payments"
                net.signum() < 0 -> "You owe $name ${PaceNudges.money(net.abs())} across $payments"
                else -> "You and $name are even across $payments"
            }
        }
}

/** The person-centred view of splits: one balance per friend. */
object FriendBalances {
    fun build(splits: List<Split>, messages: List<ClassifiedMessage>, zone: ZoneId): List<FriendBalance> =
        fromShares(SplitInsights.build(splits, messages, zone), messages, zone)

    fun fromShares(shares: List<SplitShare>, messages: List<ClassifiedMessage>, zone: ZoneId): List<FriendBalance> {
        val dates = messages.associate { it.id to Instant.ofEpochMilli(it.message.date).atZone(zone).toLocalDate() }
        return shares.groupBy { it.person.name.trim().lowercase() }.map { (_, group) ->
            val open = group.filter { !it.settled }
            val activity = group.mapNotNull { it.settledOn ?: dates[it.split.messageId] }.maxOrNull()
            FriendBalance(
                name = group.first().person.name.trim(),
                owedToYou = open.filter { it.split.paidBy == SplitPayer.ME }.sumShares(),
                youOwe = open.filter { it.split.paidBy == SplitPayer.FRIEND }.sumShares(),
                open = open.sortedByDescending { dates[it.split.messageId] ?: LocalDate.MIN },
                settledCount = group.size - open.size,
                lastActivity = activity
            )
        }.sortedWith(compareBy<FriendBalance> { it.isSettled }.thenByDescending { it.net.abs() }.thenBy { it.name.lowercase() })
    }

    /** Marks every open share of [name] as settled by hand and returns the splits that changed, ready to save. */
    fun settleAll(splits: List<Split>, shares: List<SplitShare>, name: String): List<Split> {
        val key = name.trim().lowercase()
        val openIds = shares.filter { !it.settled && it.person.name.trim().lowercase() == key }.map { it.split.id }.toSet()
        return splits.filter { it.id in openIds }.map { split ->
            split.copy(people = split.people.map { if (it.name.trim().lowercase() == key) it.copy(settledByHand = true) else it })
        }
    }

    private fun List<SplitShare>.sumShares(): BigDecimal = fold(BigDecimal.ZERO) { sum, s -> sum + s.person.share }
}
