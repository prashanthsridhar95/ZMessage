package com.zyke.message.domain.flight

import com.zyke.message.domain.backup.MiniJson
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** What reading the live source's reply for one flight came to. */
sealed interface FlightLookup {
    data class Found(val status: LiveStatus) : FlightLookup
    /** A readable reply with no flight of that number on that day. */
    data object NotFound : FlightLookup
    /** The reply was not what this parser expects (the source changed, or it was an error page). */
    data object Unreadable : FlightLookup
}

/**
 * Reads the reply of Flightradar24's public flight-history endpoint for one flight number
 * (`https://api.flightradar24.com/common/v1/flight/list.json?query=6E2345&fetchBy=flight&page=1&limit=25`).
 *
 * UNVERIFIED AGAINST THE LIVE SERVICE: written from the reply's known shape (`result.response.data[]`, each with
 * `identification`, `status`, `airline`, `airport.origin/destination` and `time.scheduled/estimated/real`, times in epoch
 * seconds), because the build environment could not reach the host. It is unofficial and may change, rate-limit or
 * block; anything unexpected gives [FlightLookup.Unreadable] and the app keeps the last known status.
 */
object FlightStatusParser {

    /** The flight number as the source's search box takes it: "6E2345". */
    fun query(airline: Airline, number: String): String = airline.code + number

    fun parse(json: String, flight: BookedFlight, fetchedAt: Long, zone: ZoneId): FlightLookup {
        val root = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return FlightLookup.Unreadable
        val response = (root["result"] as? Map<*, *>)?.get("response") as? Map<*, *> ?: return FlightLookup.Unreadable
        val data = response["data"] as? List<*> ?: return if (response.containsKey("data")) FlightLookup.NotFound else FlightLookup.Unreadable
        val onDay = data.mapNotNull { it as? Map<*, *> }.filter { matchesDay(it, flight, zone) }
        if (onDay.isEmpty()) return FlightLookup.NotFound
        val wanted = flight.origin?.code
        val leg = onDay.firstOrNull { wanted != null && code(it, "origin") == wanted } ?: onDay.first()
        return leg(leg, fetchedAt)?.let { FlightLookup.Found(it) } ?: FlightLookup.Unreadable
    }

    private fun get(map: Map<*, *>?, vararg path: String): Any? {
        var current: Any? = map
        for (step in path) current = (current as? Map<*, *>)?.get(step) ?: return null
        return current
    }

    private fun seconds(map: Map<*, *>, vararg path: String): Instant? =
        (get(map, *path) as? Number)?.toLong()?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }

    private fun text(map: Map<*, *>, vararg path: String): String? = (get(map, *path) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun code(map: Map<*, *>, end: String): String? = text(map, "airport", end, "code", "iata")?.uppercase()

    private fun matchesDay(leg: Map<*, *>, flight: BookedFlight, zone: ZoneId): Boolean {
        val departs = seconds(leg, "time", "scheduled", "departure") ?: return false
        val offset = (get(leg, "airport", "origin", "timezone", "offset") as? Number)?.toInt()
        val legZone: ZoneId = offset?.let { ZoneOffset.ofTotalSeconds(it) } ?: flight.departureZone(zone)
        val number = text(leg, "identification", "number", "default")?.uppercase()?.replace(" ", "")
        if (number != null && number != flight.airline.code + flight.number) return false
        return departs.atZone(legZone).toLocalDate() == flight.date
    }

    private fun leg(leg: Map<*, *>, fetchedAt: Long): LiveStatus? {
        val scheduledDeparture = seconds(leg, "time", "scheduled", "departure")
        val estimatedDeparture = seconds(leg, "time", "estimated", "departure")
        val actualDeparture = seconds(leg, "time", "real", "departure")
        val scheduledArrival = seconds(leg, "time", "scheduled", "arrival")
        val estimatedArrival = seconds(leg, "time", "estimated", "arrival") ?: seconds(leg, "time", "other", "eta")
        val actualArrival = seconds(leg, "time", "real", "arrival")
        val statusText = (text(leg, "status", "generic", "status", "text") ?: text(leg, "status", "text")).orEmpty().lowercase()
        val live = get(leg, "status", "live") == true
        val delay = delayMinutes(scheduledDeparture, actualDeparture ?: estimatedDeparture)
        val kind = when {
            "cancel" in statusText -> FlightStatusKind.CANCELLED
            actualArrival != null || "landed" in statusText -> FlightStatusKind.LANDED
            live -> FlightStatusKind.IN_AIR
            actualDeparture != null || "departed" in statusText -> FlightStatusKind.DEPARTED
            delay >= FlightTracker.DELAY_THRESHOLD_MINUTES || "delayed" in statusText -> FlightStatusKind.DELAYED
            else -> FlightStatusKind.SCHEDULED
        }
        if (scheduledDeparture == null && kind == FlightStatusKind.SCHEDULED) return null
        return LiveStatus(
            kind = kind,
            scheduledDeparture = scheduledDeparture, estimatedDeparture = estimatedDeparture, actualDeparture = actualDeparture,
            scheduledArrival = scheduledArrival, estimatedArrival = estimatedArrival, actualArrival = actualArrival,
            originCode = code(leg, "origin"), destinationCode = code(leg, "destination"),
            terminal = text(leg, "airport", "origin", "info", "terminal"),
            gate = text(leg, "airport", "origin", "info", "gate"),
            arrivalTerminal = text(leg, "airport", "destination", "info", "terminal"),
            baggage = text(leg, "airport", "destination", "info", "baggage"),
            delayMinutes = delay,
            updatedAt = fetchedAt
        )
    }

    private fun delayMinutes(scheduled: Instant?, now: Instant?): Int {
        if (scheduled == null || now == null) return 0
        return Duration.between(scheduled, now).toMinutes().toInt().coerceIn(0, 24 * 60)
    }
}

/** The words of the card and the sheet, in one place so they are tested. Times are on each airport's own clock, 24-hour. */
object FlightText {
    private val HOURS_MINUTES = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    fun clock(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(HOURS_MINUTES)

    /** "Chennai → Delhi", or just what is known. */
    fun route(s: FlightSnapshot): String {
        val from = s.origin?.city
        val to = s.destination?.city
        return when {
            from != null && to != null -> "$from → $to"
            else -> from ?: to ?: ""
        }
    }

    /** "IndiGo 6E 2345 · Chennai → Delhi". */
    fun title(s: FlightSnapshot): String = listOf("${s.flight.airline.name} ${s.flight.code}", route(s)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** The arrival clock, with "+1" when it is on a later day than the departure. */
    fun arrivalClock(s: FlightSnapshot): String? {
        val arrives = s.bestArrival ?: return null
        val sameDay = s.bestDeparture?.let { d -> d.atZone(s.departureZone).toLocalDate() == arrives.atZone(s.arrivalZone).toLocalDate() } ?: true
        return clock(arrives, s.arrivalZone) + if (sameDay) "" else " +1"
    }

    /** The one line under the title: what matters now, in a few words. */
    fun headline(s: FlightSnapshot, now: Instant): String {
        val dep = s.bestDeparture
        return when (s.kind) {
            FlightStatusKind.CANCELLED -> "Cancelled · check with the airline"
            FlightStatusKind.LANDED -> arrivalClock(s)?.let { "Landed · $it" } ?: "Landed"
            FlightStatusKind.IN_AIR, FlightStatusKind.DEPARTED -> {
                val lands = s.bestArrival
                when {
                    lands != null && lands.isAfter(now) -> "Lands ${arrivalClock(s)} · ${Countdown.until(now, lands)}"
                    lands != null -> "Lands ${arrivalClock(s)}"
                    dep != null -> "Left ${clock(dep, s.departureZone)}"
                    else -> s.kind.label
                }
            }
            FlightStatusKind.DELAYED -> {
                val tail = dep?.let { " · new ${clock(it, s.departureZone)}" }.orEmpty()
                "Delayed ${delayWords(s.delayMinutes)}$tail"
            }
            FlightStatusKind.BOARDING -> dep?.let { "Boarding · departs ${clock(it, s.departureZone)}" } ?: "Boarding now"
            FlightStatusKind.SCHEDULED, FlightStatusKind.CHECK_IN -> when {
                dep == null -> "Departure time not in your message"
                dep.isAfter(now) -> "Departs ${clock(dep, s.departureZone)} · ${Countdown.until(now, dep)}"
                else -> "Was due ${clock(dep, s.departureZone)} · ${Countdown.until(now, dep)}"
            }
        }
    }

    /** "25 min", "1 h 5 min". */
    fun delayWords(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** "Terminal 3 · Gate 12", or null when neither is known. */
    fun place(s: FlightSnapshot): String? =
        listOfNotNull(s.terminal?.let { "Terminal $it" }, s.gate?.let { "Gate $it" }).joinToString(" · ").ifEmpty { null }

    /** "Updated just now", "Updated 12 s ago", "Updated 3 min ago". */
    fun updated(now: Long, updatedAt: Long): String {
        val seconds = ((now - updatedAt) / 1000).coerceAtLeast(0)
        return when {
            seconds < 5 -> "Updated just now"
            seconds < 60 -> "Updated $seconds s ago"
            seconds < 3600 -> "Updated ${seconds / 60} min ago"
            else -> "Updated ${seconds / 3600} h ago"
        }
    }

    /**
     * How far along the flight is, 0..1, for the sheet's progress line: from the actual or planned departure to the actual or
     * planned arrival. 0 before departure, 1 once landed.
     */
    fun progress(s: FlightSnapshot, now: Instant): Float {
        if (s.kind == FlightStatusKind.LANDED) return 1f
        if (s.kind == FlightStatusKind.CANCELLED) return 0f
        val dep = s.bestDeparture ?: return 0f
        val arr = s.bestArrival ?: return if (s.kind == FlightStatusKind.IN_AIR || s.kind == FlightStatusKind.DEPARTED) 0.5f else 0f
        if (!now.isAfter(dep) && s.kind != FlightStatusKind.IN_AIR && s.kind != FlightStatusKind.DEPARTED) return 0f
        val total = Duration.between(dep, arr).toMillis().coerceAtLeast(1)
        val done = Duration.between(dep, now).toMillis()
        return (done.toFloat() / total).coerceIn(if (s.kind == FlightStatusKind.IN_AIR || s.kind == FlightStatusKind.DEPARTED) 0.03f else 0f, 0.97f)
    }
}
