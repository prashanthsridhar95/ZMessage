package com.zyke.message.domain.insights

import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.life.LifeAgenda
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserMessageState
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A few lines for the morning: what is due, what is on, what is coming. Null when there is nothing worth saying. */
data class DigestSummary(val title: String, val lines: List<String>) {
    /** One-line text for places with room for little, such as a collapsed notification. */
    val short: String get() = lines.first()
}

object DailyDigest {
    private const val DUE_SOON_DAYS = 3L
    private const val MAX_LINES = 5

    fun build(messages: List<ClassifiedMessage>, userState: UserMessageState, now: Instant, zone: ZoneId): DigestSummary? {
        val today = LocalDate.ofInstant(now, zone)
        val live = messages.filter { it.id !in userState.archivedIds && !it.isPromotional }
        val lines = mutableListOf<String>()

        UpcomingBuilder.build(live, today).filter { !it.date.isAfter(today.plusDays(DUE_SOON_DAYS)) }.forEach { due ->
            lines += "${due.title} ${rupees(due.amount)} ${dayWord(due.date, today)}"
        }

        LifeAgenda.build(live, today, zone).forEach { item ->
            lines += when {
                item.date != null -> "${item.title}: ${item.status.lowercase()} ${dayWord(item.date!!, today)}"
                item.kind == LifeKind.DELIVERY -> "${item.title} order: ${item.status.lowercase()}"
                else -> "${item.title}: ${item.status.lowercase()}"
            }
        }

        val yesterday = today.minusDays(1)
        val spentYesterday = live.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            val day = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (day == yesterday && !tx.isReceived) tx.amount.toBigDecimalOrNull() else null
        }.fold(BigDecimal.ZERO, BigDecimal::add)
        if (spentYesterday.signum() > 0) lines += "You spent ${rupees(spentYesterday)} yesterday"

        if (lines.isEmpty()) return null
        return DigestSummary("Your day", lines.take(MAX_LINES))
    }

    private fun dayWord(date: LocalDate, today: LocalDate) = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> "on ${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}"
    }

    private fun rupees(amount: BigDecimal): String {
        val whole = amount.stripTrailingZeros().scale() <= 0
        val format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = if (whole) 0 else 2
        }
        return "₹" + format.format(amount)
    }
}
