package com.zyke.message.domain.cards

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserCard
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One billing cycle of a card: what was spent and what came back in. Both ends are inclusive. */
data class CardCycle(
    val start: LocalDate,
    val end: LocalDate,
    val spent: BigDecimal,
    val spendCount: Int,
    /** Money credited to the card in the cycle: bill payments, refunds. */
    val paid: BigDecimal,
    val isCurrent: Boolean
)

/** A card with its cycles, newest first. */
data class CardCycles(val card: UserCard, val cycles: List<CardCycle>)

object CardCycleBuilder {
    const val CYCLES_SHOWN = 6

    fun build(
        messages: List<ClassifiedMessage>,
        cards: List<UserCard>,
        now: Instant,
        zone: ZoneId,
        count: Int = CYCLES_SHOWN
    ): List<CardCycles> {
        val today = LocalDate.ofInstant(now, zone)
        return cards.map { card ->
            val moves = messages.mapNotNull { message ->
                val content = message.content as? MessageContent.Transaction ?: return@mapNotNull null
                if (message.cardId != card.id || message.setAside != null) return@mapNotNull null
                val amount = content.amount.toBigDecimalOrNull() ?: return@mapNotNull null
                Triple(LocalDate.ofInstant(Instant.ofEpochMilli(message.message.date), zone), amount, content.isReceived)
            }

            var end = cycleEnd(today, card.statementDay)
            val cycles = buildList {
                repeat(count) { index ->
                    val start = cycleStart(end, card.statementDay)
                    val inCycle = moves.filter { (date, _, _) -> !date.isBefore(start) && !date.isAfter(end) }
                    val spent = inCycle.filter { !it.third }
                    add(
                        CardCycle(
                            start = start,
                            end = end,
                            spent = spent.sumOf { it.second },
                            spendCount = spent.size,
                            paid = inCycle.filter { it.third }.sumOf { it.second },
                            isCurrent = index == 0
                        )
                    )
                    end = start.minusDays(1)
                }
            }
            CardCycles(card, cycles)
        }
    }

    /** The last day of the cycle that contains [date]. */
    fun cycleEnd(date: LocalDate, statementDay: Int?): LocalDate {
        if (statementDay == null) return date.withDayOfMonth(date.lengthOfMonth())
        val thisMonth = clamp(YearMonth.from(date), statementDay)
        return if (!date.isAfter(thisMonth)) thisMonth else clamp(YearMonth.from(date).plusMonths(1), statementDay)
    }

    /** The first day of the cycle that ends on [end]: the day after the previous statement date. */
    fun cycleStart(end: LocalDate, statementDay: Int?): LocalDate {
        if (statementDay == null) return end.withDayOfMonth(1)
        return clamp(YearMonth.from(end).minusMonths(1), statementDay).plusDays(1)
    }

    private fun clamp(month: YearMonth, day: Int): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))
}
