package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import java.time.LocalDate
import java.time.LocalTime

/**
 * The journeys found in the person's texts, written down so an alarm or the service can work without reading the inbox. It keeps
 * what tracking needs and nothing else: no PNR, no message text. One journey a line, fields separated by a tab. Anything unreadable
 * is skipped.
 */
object TrainPlanCodec {
    private const val FIELDS = 18

    fun encode(trains: List<BookedTrain>): String = trains.joinToString("\n") { t ->
        listOf(
            t.number, t.name.orEmpty(), t.date.toString(), place(t.from), place(t.to), t.departure?.toString().orEmpty(), t.arrival?.toString().orEmpty(),
            t.arrivalDayOffset.toString(), t.trainClass.orEmpty(), t.coach.orEmpty(), t.berth.orEmpty(), t.ticket.orEmpty(), t.platform.orEmpty(),
            t.status.name, t.delayMinutes?.toString().orEmpty(), t.newDeparture?.toString().orEmpty(), if (t.ticketCancelled) "1" else "0",
            t.boardingDay.toString()
        ).joinToString("\t") { it.clean() }
    }

    fun decode(text: String): List<BookedTrain> = text.lineSequence().mapNotNull { line ->
        runCatching {
            val p = line.split('\t')
            if (p.size < FIELDS) return@runCatching null
            fun opt(i: Int) = p[i].takeIf { it.isNotEmpty() }
            BookedTrain(
                number = p[0], name = opt(1), date = LocalDate.parse(p[2]), from = readPlace(p[3]), to = readPlace(p[4]),
                departure = opt(5)?.let { LocalTime.parse(it) }, arrival = opt(6)?.let { LocalTime.parse(it) }, arrivalDayOffset = p[7].toIntOrNull() ?: 0,
                trainClass = opt(8), coach = opt(9), berth = opt(10), ticket = opt(11), pnr = null, platform = opt(12),
                status = TrainStatusKind.valueOf(p[13]), delayMinutes = p[14].toIntOrNull(), newDeparture = opt(15)?.let { LocalTime.parse(it) },
                ticketCancelled = p[16] == "1", boardingDay = p[17].toIntOrNull() ?: 1
            )
        }.getOrNull()
    }.toList()

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun place(p: Place?): String = if (p == null) "" else "${p.code.orEmpty()}:${p.city}"

    private fun readPlace(text: String): Place? {
        if (text.isEmpty()) return null
        val code = text.substringBefore(':').takeIf { it.isNotEmpty() }
        val city = text.substringAfter(':', "")
        return if (code == null && city.isEmpty()) null else Place(code, city.ifEmpty { code.orEmpty() })
    }
}
