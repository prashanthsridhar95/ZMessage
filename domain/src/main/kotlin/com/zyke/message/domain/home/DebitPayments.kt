package com.zyke.message.domain.home

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** That an announced auto-debit has now happened. */
object DebitPayments {
    private const val EARLY_DAYS = 3L
    private const val LATE_DAYS = 7L
    private val TOLERANCE = BigDecimal("0.02")
    private val ONE = BigDecimal.ONE

    /**
     * The debit that followed the announcement: money sent for about the announced amount, to the same name, from
     * a few days before the due day to a week after it.
     */
    fun find(
        announcement: ClassifiedMessage,
        payee: String,
        amount: BigDecimal,
        due: java.time.LocalDate,
        messages: List<ClassifiedMessage>,
        zone: ZoneId
    ): BillPayment? {
        val words = payee.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        val room = amount.multiply(TOLERANCE).max(ONE)
        return messages
            .filter { it.message.date > announcement.message.date && it.id != announcement.id }
            .sortedBy { it.message.date }
            .firstNotNullOfOrNull { message ->
                val tx = message.content as? MessageContent.Transaction ?: return@firstNotNullOfOrNull null
                if (tx.isReceived || tx.isCardPayment) return@firstNotNullOfOrNull null
                val paid = tx.amount.toBigDecimalOrNull() ?: return@firstNotNullOfOrNull null
                if ((paid - amount).abs() > room) return@firstNotNullOfOrNull null
                val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
                if (date.isBefore(due.minusDays(EARLY_DAYS)) || date.isAfter(due.plusDays(LATE_DAYS))) return@firstNotNullOfOrNull null
                val who = "${tx.payee} ${message.merchant.name} ${message.message.body}".lowercase()
                // With a name to go on, it has to be that name; an announcement with none is matched by the amount alone.
                if (words.isNotEmpty() && words.none { it in who }) return@firstNotNullOfOrNull null
                BillPayment(date, paid, message.id)
            }
    }
}
