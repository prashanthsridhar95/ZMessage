package com.zyke.message.domain.flight

import java.time.Duration
import java.time.Instant

/** How an endpoint's time compares with the schedule, for the emphasis the card gives it. */
enum class TimeShift { ON_SCHEDULE, LATER, EARLIER }

/** One end of the route as the card draws it. Everything is already a string, so the screen has no logic of its own. */
data class FlightEndpoint(
    /** The big letters: "DEL"; the city when no code is known; "—" when nothing is. */
    val code: String,
    /** The small name under the code, or null when it adds nothing (same as the code, or unknown). */
    val city: String?,
    /** The time to read, "18:40" (with " +1" for a next-day landing); null when none is known. */
    val time: String?,
    /** The schedule's time, drawn struck through, only when [time] has moved from it; otherwise null. */
    val was: String?,
    val shift: TimeShift
)

/**
 * Everything the Home card, its collapsed rows and the sheet header say about one flight, worked out in one tested place.
 * Times are on each airport's own clock. Nothing here is logged.
 */
data class FlightCardModel(
    val key: String,
    val dismissKey: String,
    val airlineCode: String,
    val airlineName: String,
    /** "6E 2345". */
    val flightCode: String,
    /** The website the airline's logo is asked of; null when it has none (see [Airlines.logoDomain]). */
    val logoDomain: String?,
    val kind: FlightStatusKind,
    val origin: FlightEndpoint,
    val destination: FlightEndpoint,
    /** How far along, 0..1, for the line between the codes. */
    val progress: Float,
    /** The little plane sits on the line (not for a cancelled or diverted flight). */
    val showPlane: Boolean,
    /** "2h 25m" in the air, when both times are known. */
    val duration: String?,
    /** The one calm line: "Departs in 2h 10m", "Boarding · Gate 14", "Landed 6 min early". */
    val supporting: String,
    /** Terminal and gate (or terminal and belt after landing), only the ones that are known: never an empty chip. */
    val chips: List<String>,
    /** "Updated just now", or "From your message" when there is no live data; null when neither applies. */
    val caption: String?,
    /** "6E 2345 · DEL → MAA · 18:40" for the collapsed row. */
    val summary: String,
    /** The one sentence a screen reader says for the card. */
    val spoken: String
) {
    val statusLabel: String get() = kind.label
    val isFinal: Boolean get() = kind.isFinal

    companion object {
                fun of(s: FlightSnapshot, now: Instant, liveOn: Boolean): FlightCardModel {
            val f = s.flight
            val moves = s.kind != FlightStatusKind.CANCELLED && s.kind != FlightStatusKind.DIVERTED
            val departClock = s.bestDeparture?.let { FlightText.clock(it, s.departureZone) }
            val arriveClock = FlightText.arrivalClock(s)
            val origin = endpoint(s.origin, departClock, s.scheduledDeparture?.let { FlightText.clock(it, s.departureZone) }, shift(s.scheduledDeparture, s.bestDeparture, moves))
            val destination = endpoint(
                s.destination, arriveClock, s.scheduledArrival?.let { FlightText.clock(it, s.arrivalZone) }, shift(s.scheduledArrival, s.bestArrival, moves)
            )
            val landed = s.kind == FlightStatusKind.LANDED
            val boardingGate = s.kind == FlightStatusKind.BOARDING && s.gate != null
            val chips = when {
                !moves -> emptyList()
                landed -> listOfNotNull(s.arrivalTerminal?.let { "Terminal $it" }, s.baggage?.let { "Belt $it" })
                else -> listOfNotNull(s.terminal?.let { "Terminal $it" }, if (boardingGate) null else s.gate?.let { "Gate $it" })
            }
            val supporting = supporting(s, now)
            val caption = when {
                s.updatedAt != null -> FlightText.updated(now.toEpochMilli(), s.updatedAt!!)
                !liveOn -> "From your message"
                else -> null
            }
            val route = if (s.origin?.code != null && s.destination?.code != null) "${s.origin!!.code} → ${s.destination!!.code}" else null
            val summary = listOfNotNull(f.code, route, if (moves) departClock else null).joinToString(" · ")
            val duration = if (s.bestDeparture != null && s.bestArrival != null) {
                Duration.between(s.bestDeparture, s.bestArrival).toMinutes().takeIf { it in 1..(30 * 60) }?.let { Countdown.span(it) }
            } else null
            val model = FlightCardModel(
                key = f.key, dismissKey = f.dismissKey, airlineCode = f.airline.code, airlineName = f.airline.name, flightCode = f.code,
                logoDomain = Airlines.logoDomain(f.airline.code), kind = s.kind, origin = origin, destination = destination,
                progress = FlightText.progress(s, now), showPlane = moves, duration = duration, supporting = supporting,
                chips = chips, caption = caption, summary = summary, spoken = ""
            )
            return model.copy(spoken = spoken(model))
        }

        /** The flight still to come or in the air first, the finished ones after, otherwise in the order given. */
        fun featuredFirst(models: List<FlightCardModel>): List<FlightCardModel> = models.sortedBy { it.isFinal }

        private fun endpoint(place: Place?, time: String?, scheduled: String?, shift: TimeShift): FlightEndpoint {
            val code = place?.label ?: "—"
            val city = place?.city?.takeIf { place.code != null && it.isNotBlank() && !it.equals(place.code, ignoreCase = true) }
            // The schedule is only worth showing when the time shown differs from it.
            val was = if (shift != TimeShift.ON_SCHEDULE && time != null && scheduled != null && !time.startsWith(scheduled)) scheduled else null
            return FlightEndpoint(code, city, time, was, if (was == null) TimeShift.ON_SCHEDULE else shift)
        }

        private fun shift(scheduled: Instant?, best: Instant?, moves: Boolean): TimeShift {
            if (!moves || scheduled == null || best == null) return TimeShift.ON_SCHEDULE
            val minutes = Duration.between(scheduled, best).toMinutes()
            return when {
                minutes >= 1 -> TimeShift.LATER
                minutes <= -1 -> TimeShift.EARLIER
                else -> TimeShift.ON_SCHEDULE
            }
        }

        private fun supporting(s: FlightSnapshot, now: Instant): String {
            val dep = s.bestDeparture
            val lands = s.bestArrival
            return when (s.kind) {
                FlightStatusKind.CANCELLED -> "Cancelled · check with the airline"
                FlightStatusKind.DIVERTED -> "Diverted · check with the airline"
                FlightStatusKind.LANDED -> {
                    val versus = FlightNotificationText.againstSchedule(s.scheduledArrival, s.bestArrival)
                    if (versus != null) "Landed $versus" else "Landed"
                }
                FlightStatusKind.IN_AIR, FlightStatusKind.DEPARTED -> when {
                    lands != null && lands.isAfter(now) -> "Lands ${Countdown.until(now, lands)}"
                    lands != null -> "Landing soon"
                    dep != null -> "Left ${FlightText.clock(dep, s.departureZone)}"
                    else -> s.kind.label
                }
                FlightStatusKind.DELAYED -> {
                    val late = "Delayed ${FlightText.delayWords(s.delayMinutes)}"
                    if (dep != null && dep.isAfter(now)) "$late · departs ${Countdown.until(now, dep)}" else late
                }
                FlightStatusKind.BOARDING -> s.gate?.let { "Boarding · Gate $it" } ?: "Boarding now"
                FlightStatusKind.SCHEDULED, FlightStatusKind.CHECK_IN -> {
                    val prefix = if (s.kind == FlightStatusKind.CHECK_IN) "Check-in open · " else ""
                    when {
                        dep == null -> prefix + "Departure time not in your message"
                        dep.isAfter(now) -> if (prefix.isEmpty()) "Departs ${Countdown.until(now, dep)}" else prefix + "departs ${Countdown.until(now, dep)}"
                        else -> prefix + "Was due ${FlightText.clock(dep, s.departureZone)} · ${Countdown.until(now, dep)}"
                    }
                }
            }
        }

        private fun spoken(m: FlightCardModel): String {
            fun name(e: FlightEndpoint) = e.city ?: e.code
            fun timeWords(verb: String, e: FlightEndpoint): String? = e.time?.let { t ->
                verb + " " + t + (e.was?.let { ", was $it" } ?: "")
            }
            val moves = m.kind != FlightStatusKind.CANCELLED && m.kind != FlightStatusKind.DIVERTED
            val landed = m.kind == FlightStatusKind.LANDED
            val route = if (m.origin.code != "—" && m.destination.code != "—") "${name(m.origin)} to ${name(m.destination)}" else null
            val times = if (moves) listOfNotNull(
                timeWords(if (landed || m.kind == FlightStatusKind.IN_AIR || m.kind == FlightStatusKind.DEPARTED) "Departed" else "Departs", m.origin),
                timeWords(if (landed) "landed" else "arrives", m.destination)
            ).joinToString(", ").ifEmpty { null } else null
            val parts = listOfNotNull(
                "${m.airlineName} ${m.flightCode}, ${m.statusLabel}", route, times, m.supporting,
                m.chips.joinToString(", ").ifEmpty { null }, m.caption
            )
            return parts.joinToString(". ")
        }
    }
}
