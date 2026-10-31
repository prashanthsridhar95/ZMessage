package com.zyke.message.domain.insights

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A payment that comes round every month. */
data class Subscription(
    val payee: String,
    val amount: BigDecimal,
    val lastPaid: LocalDate,
    val nextDue: LocalDate,
    val times: Int,
    /** The payment's own message, to open. */
    val messageId: Long
) {
    fun daysUntilDue(today: LocalDate): Long = ChronoUnit.DAYS.between(today, nextDue)

    /** Stable identity for the person's confirm / ignore choice. */
    val key: String get() = Subscriptions.keyFor(payee)
}

/**
 * Finds payments that repeat monthly: the same payee three or more times, about a month apart and about the same
 * size. It can only remind: the app cannot pay, so this is the "alerted payment" part of recurring payments.
 */
object Subscriptions {
    private const val MIN_TIMES = 3
    private const val MIN_GAP = 24L
    private const val MAX_GAP = 38L
    private val SIZE_TOLERANCE = BigDecimal("0.25")
    private const val LOOKBACK_MONTHS = 8L

    fun find(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId): List<Subscription> {
        val today = LocalDate.ofInstant(now, zone)
        val earliest = today.minusMonths(LOOKBACK_MONTHS)

        val payments = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (tx.isReceived || tx.payee.isBlank()) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (date.isBefore(earliest) || date.isAfter(today)) return@mapNotNull null
            Payment(key(tx.payee), message.merchant.name.ifEmpty { tx.payee }, amount, date, message.id)
        }

        return payments.groupBy { it.key }.mapNotNull { (_, group) -> subscription(group, today) }
            .sortedBy { it.nextDue }
    }

    private fun subscription(group: List<Payment>, today: LocalDate): Subscription? {
        // One payment per month: the latest, so a double charge does not look like two months.
        val perMonth = group.groupBy { it.date.year * 12 + it.date.monthValue }.map { (_, p) -> p.maxBy { it.date } }.sortedBy { it.date }
        if (perMonth.size < MIN_TIMES) return null

        val gaps = perMonth.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (gaps.any { it !in MIN_GAP..MAX_GAP }) return null

        val amounts = perMonth.map { it.amount }.sorted()
        val middle = amounts[amounts.size / 2]
        if (middle.signum() <= 0 || amounts.any { (it - middle).abs() > middle.multiply(SIZE_TOLERANCE) }) return null

        val last = perMonth.last()
        val averageGap = gaps.average().toLong()
        var next = last.date.plusDays(averageGap)
        // A subscription that has stopped is not "due"; give it a month's grace and then drop it.
        if (next.isBefore(today.minusDays(MAX_GAP))) return null
        while (next.isBefore(today)) next = next.plusMonths(1)
        return Subscription(last.name, last.amount.setScale(0, RoundingMode.HALF_UP), last.date, next, perMonth.size, last.messageId)
    }

    fun keyFor(payee: String) = key(payee)

    private fun key(payee: String) = payee.lowercase().replace(Regex("[^a-z@. ]+"), " ").replace(Regex("\\s+"), " ").trim()

    private class Payment(val key: String, val name: String, val amount: BigDecimal, val date: LocalDate, val messageId: Long)
}
