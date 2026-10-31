package com.zyke.message.domain.insights

import com.zyke.message.domain.forecast.UpcomingOutflow
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class WeeklyPayment(val payee: String, val amount: BigDecimal)

/** The week that just ended, against the one before, and what is due in the week ahead. */
data class WeeklySummary(
    val spent: BigDecimal,
    val spentLastWeek: BigDecimal,
    val topCategory: MessageCategory?,
    val topCategoryAmount: BigDecimal,
    val biggest: WeeklyPayment?,
    val dueNext: UpcomingOutflow
) {
    /** Whole-percent change from last week; null when last week had no spending to compare with. */
    val changePercent: Int?
        get() = if (spentLastWeek.signum() <= 0) null else
            spent.subtract(spentLastWeek).multiply(BigDecimal(100)).divide(spentLastWeek, 0, java.math.RoundingMode.HALF_UP).toInt()

    val isQuiet: Boolean get() = spent.signum() == 0 && spentLastWeek.signum() == 0 && dueNext.isEmpty

    companion object {
        /**
         * "This week" is the seven days ending [now]'s day, "last week" the seven before it, so the weekdays line up.
         * [dueNext] is the forecast for the seven days that follow.
         */
        fun build(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId, dueNext: UpcomingOutflow): WeeklySummary {
            val today = LocalDate.ofInstant(now, zone)
            val thisStart = today.minusDays(6)
            val lastStart = today.minusDays(13)
            val debits = messages.mapNotNull { m ->
                if (m.isPromotional || m.isMuted || m.setAside != null) return@mapNotNull null
                val tx = m.content as? MessageContent.Transaction ?: return@mapNotNull null
                if (tx.isReceived || tx.isCardPayment) return@mapNotNull null
                val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
                Triple(m, tx, amount) to LocalDate.ofInstant(Instant.ofEpochMilli(m.message.date), zone)
            }
            val thisWeek = debits.filter { it.second in thisStart..today }.map { it.first }
            val lastWeek = debits.filter { it.second in lastStart..thisStart.minusDays(1) }.map { it.first }
            val top = thisWeek.groupBy { it.first.category }.mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { s, t -> s + t.third } }
                .maxByOrNull { it.value }
            val biggest = thisWeek.maxByOrNull { it.third }?.let { WeeklyPayment(it.second.payee.ifBlank { "a payee" }, it.third) }
            return WeeklySummary(
                spent = thisWeek.fold(BigDecimal.ZERO) { s, t -> s + t.third },
                spentLastWeek = lastWeek.fold(BigDecimal.ZERO) { s, t -> s + t.third },
                topCategory = top?.key,
                topCategoryAmount = top?.value ?: BigDecimal.ZERO,
                biggest = biggest,
                dueNext = dueNext
            )
        }
    }
}
