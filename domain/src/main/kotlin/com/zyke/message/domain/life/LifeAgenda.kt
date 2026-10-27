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

        // One line per order: its newest status, unless it has already arrived or is old news.
        val orders = messages.mapNotNull { message ->
            val life = message.content as? MessageContent.Life ?: return@mapNotNull null
            if (life.kind != LifeKind.DELIVERY) return@mapNotNull null
            val seen = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (seen.isBefore(today.minusDays(TRANSIT_DAYS))) return@mapNotNull null
            Triple(life.reference ?: "${life.title}-$seen", message, life)
        }.distinctBy { it.first }
            .filter { !it.third.isDone }
            .map { (_, message, life) -> AgendaItem(message, LifeKind.DELIVERY, life.title, life.status, null, null) }

        return (dated.sortedWith(compareBy({ it.date }, { it.time })) + orders).distinctBy { it.message.id }.take(MAX_ITEMS)
    }
}
