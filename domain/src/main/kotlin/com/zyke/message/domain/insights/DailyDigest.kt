package com.zyke.message.domain.insights

import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.life.LifeAgenda
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.reminders.Reminders
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class DigestKind { REMINDER, BILL, DEBIT, EVENT, ORDER, SPENT }

/** One thing in the digest, kept in parts so a widget or notification can lay it out properly. */
data class DigestItem(
    val kind: DigestKind,
    val title: String,
    /** When or how: "today", "tomorrow", "out for delivery". */
    val detail: String,
    val amount: BigDecimal? = null,
    /** Due today or overdue; drawn to stand out. */
    val urgent: Boolean = false
) {
    /** The same thing as one plain line. */
    val text: String get() = listOfNotNull(title, amount?.let { DigestFormat.rupees(it) }, detail.takeIf { it.isNotBlank() }).joinToString(" ")
}

/** A few things for the morning: what is due, what is on, what is coming. Null when there is nothing worth saying. */
data class DigestSummary(val title: String, val items: List<DigestItem>, val spentYesterday: BigDecimal? = null) {
    /** Plain lines, for places with no room to lay things out. */
    val lines: List<String> get() = items.map { it.text }

    /** What is about to leave: card bills and auto-debits in the digest, added up. */
    val dueTotal: BigDecimal
        get() = items.filter { it.kind == DigestKind.BILL || it.kind == DigestKind.DEBIT }.mapNotNull { it.amount }.fold(BigDecimal.ZERO, BigDecimal::add)

    val dueCount: Int get() = items.count { it.kind == DigestKind.BILL || it.kind == DigestKind.DEBIT }

    /** One-line text for places with room for little, such as a collapsed notification. */
    val short: String get() = lines.firstOrNull() ?: "Nothing due or on today"
}

internal object DigestFormat {
    fun rupees(amount: BigDecimal): String {
        val whole = amount.stripTrailingZeros().scale() <= 0
        val format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = if (whole) 0 else 2
        }
        return "\u20b9" + format.format(amount)
    }
}

object DailyDigest {
    private const val DUE_SOON_DAYS = 3L
    private const val MAX_LINES = 6

    fun build(
        messages: List<ClassifiedMessage>,
        userState: UserMessageState,
        now: Instant,
        zone: ZoneId,
        reminders: List<Reminder> = emptyList(),
        events: List<com.zyke.message.domain.calendar.CalendarEvent> = emptyList()
    ): DigestSummary? {
        val today = LocalDate.ofInstant(now, zone)
        val live = messages.filter { it.id !in userState.archivedIds && !it.isPromotional }
        val items = mutableListOf<DigestItem>()

        // What the person asked to be reminded of comes first.
        Reminders.upcoming(reminders, today, withinDays = 1).forEach { due ->
            items += DigestItem(DigestKind.REMINDER, "Reminder: ${due.reminder.title}", dayWord(due.on, today), due.reminder.amount, due.on == today)
        }

        // So are the person's own events, today and tomorrow, with the time when there is one.
        events.filter { it.date == today || it.date == today.plusDays(1) }.sortedWith(compareBy({ it.date }, { it.time })).forEach { event ->
            val at = event.time?.let { " at " + java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(it) }.orEmpty()
            items += DigestItem(DigestKind.EVENT, event.title, dayWord(event.date, today) + at, null, event.date == today)
        }

        UpcomingBuilder.build(live, today).filter { !it.date.isAfter(today.plusDays(DUE_SOON_DAYS)) }.forEach { due ->
            val kind = if (due.kind == com.zyke.message.domain.home.UpcomingKind.CARD_BILL) DigestKind.BILL else DigestKind.DEBIT
            items += DigestItem(kind, due.title, "due ${dayWord(due.date, today)}", due.amount, !due.date.isAfter(today))
        }

        LifeAgenda.build(live, today, zone).forEach { item ->
            items += when {
                item.date != null -> DigestItem(DigestKind.EVENT, item.title, "${item.status.lowercase()} ${dayWord(item.date!!, today)}", null, item.date == today)
                item.kind == LifeKind.DELIVERY -> DigestItem(DigestKind.ORDER, "${item.title} order", item.status.lowercase())
                else -> DigestItem(DigestKind.ORDER, item.title, item.status.lowercase())
            }
        }

        val yesterday = today.minusDays(1)
        val spentYesterday = live.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            val day = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (day == yesterday && !tx.isReceived && !tx.isCardPayment) tx.amount.toBigDecimalOrNull() else null
        }.fold(BigDecimal.ZERO, BigDecimal::add)
        if (spentYesterday.signum() > 0) items += DigestItem(DigestKind.SPENT, "You spent", "yesterday", spentYesterday)

        if (items.isEmpty()) return null
        return DigestSummary("Your day", items.take(MAX_LINES), spentYesterday.takeIf { it.signum() > 0 })
    }

    private fun dayWord(date: LocalDate, today: LocalDate) = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> "on ${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}"
    }
}
