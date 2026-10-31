package com.zyke.message.domain.life

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One thing coming up, or on its way. */
data class AgendaItem(
    val message: ClassifiedMessage,
    val kind: LifeKind,
    val title: String,
    val status: String,
    /** Null for a delivery in transit, which has no day of its own. */
    val date: LocalDate?,
    val time: LocalTime?
)

/** "Your days ahead": what is happening soon, and what is on its way to the door. */
object LifeAgenda {
    private const val HORIZON_DAYS = 14L
    private const val TRANSIT_DAYS = 5L

    /** "Out for delivery" is news for that day and the next, not for the rest of the week. */
    private const val OUT_FOR_DELIVERY_DAYS = 1L
    private const val MAX_ITEMS = 6

    /** @param messages newest first */
    fun build(messages: List<ClassifiedMessage>, today: LocalDate, zone: ZoneId): List<AgendaItem> {
        val horizon = today.plusDays(HORIZON_DAYS)

        val dated = messages.mapNotNull { message ->
            val life = message.content as? MessageContent.Life ?: return@mapNotNull null
            val date = life.date ?: return@mapNotNull null
            if (life.isDone || life.kind == LifeKind.DELIVERY) return@mapNotNull null
            if (date.isBefore(today) || date.isAfter(horizon)) return@mapNotNull null
            AgendaItem(message, life.kind, life.title, life.status, date, life.time)
        }

        val windows = Warranties.all(messages, zone).filter { !it.endsOn.isBefore(today) && !it.endsOn.isAfter(horizon) }.map {
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, it.endsOn)
            val status = when (days) {
                0L -> "ends today"
                1L -> "ends tomorrow"
                else -> "ends in $days days"
            }
            AgendaItem(it.message, LifeKind.WARRANTY, it.title, status, it.endsOn, null)
        }

        // One line per order: its newest status, unless it has already arrived or is old news.
        val orders = messages.mapNotNull { message ->
            val life = message.content as? MessageContent.Life ?: return@mapNotNull null
            if (life.kind != LifeKind.DELIVERY) return@mapNotNull null
            val seen = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            // Orders are told apart by their reference, else by what they are, so a later "delivered" from the same shop
            // closes an earlier "out for delivery" instead of sitting beside it.
            Triple(life.reference ?: life.title.lowercase(), message, life)
        }.distinctBy { it.first }
            .filter { !it.third.isDone }
            .filter { (_, message, life) ->
                val seen = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
                val days = if (life.status.contains("out for delivery", ignoreCase = true)) OUT_FOR_DELIVERY_DAYS else TRANSIT_DAYS
                !seen.isBefore(today.minusDays(days))
            }
            .map { (_, message, life) -> AgendaItem(message, LifeKind.DELIVERY, life.title, life.status, null, null) }

        return ((dated + windows).sortedWith(compareBy({ it.date }, { it.time })) + orders).distinctBy { it.message.id to (it.kind == LifeKind.WARRANTY) }.take(MAX_ITEMS)
    }
}
