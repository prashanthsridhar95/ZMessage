package com.zyke.message.domain.flight

import java.time.LocalDate
import java.time.LocalTime

/**
 * The flights found in the person's texts, written down so an alarm or the service can work without reading the inbox. It keeps
 * what tracking needs (airline, number, day, route, scheduled times and what the texts said about a delay) and nothing else:
 * no PNR, no message text. One flight a line, fields separated by a tab. Anything unreadable is skipped.
 */
object FlightPlanCodec {
    private const val FIELDS = 12

    fun encode(flights: List<BookedFlight>): String = flights.joinToString("\n") { f ->
        listOf(
            f.airline.code, f.number, f.date.toString(), place(f.origin), place(f.destination),
            f.departure?.toString().orEmpty(), f.arrival?.toString().orEmpty(), f.status.name,
            f.delayMinutes?.toString().orEmpty(), f.newDeparture?.toString().orEmpty(),
            f.terminal.orEmpty().clean(), f.gate.orEmpty().clean()
        ).joinToString("\t") { it.clean() }
    }

    fun decode(text: String): List<BookedFlight> = text.lineSequence().mapNotNull { line ->
        runCatching {
            val p = line.split('\t')
            if (p.size < FIELDS) return@runCatching null
            val airline = Airlines.byCode(p[0]) ?: Airline(p[0], p[0])
            BookedFlight(
                airline = airline, number = p[1], date = LocalDate.parse(p[2]), origin = readPlace(p[3]), destination = readPlace(p[4]),
                departure = p[5].takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                arrival = p[6].takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                pnr = null, terminal = p[10].takeIf { it.isNotEmpty() }, gate = p[11].takeIf { it.isNotEmpty() },
                status = FlightStatusKind.valueOf(p[7]), delayMinutes = p[8].toIntOrNull(),
                newDeparture = p[9].takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) }
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
