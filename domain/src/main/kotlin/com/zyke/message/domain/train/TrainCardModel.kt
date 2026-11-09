package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Countdown
import com.zyke.message.domain.flight.FlightEndpoint
import com.zyke.message.domain.flight.FlightText
import com.zyke.message.domain.flight.TimeShift
import java.time.Duration
import java.time.Instant

/**
 * Everything the Home card, its collapsed rows and the sheet header say about one train journey, worked out in one tested place.
 * Times are on the Indian clock. Nothing here is logged. The route is the person's own: where they board and where they get off.
 */
data class TrainCardModel(
    val key: String,
    val dismissKey: String,
    /** "12633". */
    val number: String,
    /** "Kanniyakumari Express", or null when no text or look-up gave it. */
    val name: String?,
    val kind: TrainStatusKind,
    /** The short words in the status pill: "Not started", "Late 25 min", "At station", "Arrived". */
    val pill: String,
    val origin: FlightEndpoint,
    val destination: FlightEndpoint,
    /** How far along, 0..1, for the line between the stations. */
    val progress: Float,
    /** The little train sits on the line (not for a cancelled or diverted train). */
    val showTrain: Boolean,
    /** "7h 5m" from boarding to getting off, when both times are known. */
    val duration: String?,
    /** The one calm line: "Reaches your station in 1h 5m", "Arrives in 3h 12m · 12 min late", "Arrived 04:41 · 6 min late". */
    val supporting: String,
    /** The second, quieter line: "Next station: Ariyalur · expected 03:10" or "Last seen at Tambaram · 20:42, 25 min late". */
    val next: String?,
    /** Platform, then class with coach and berth: only the ones that are known, never an empty chip. */
    val chips: List<String>,
    /** "Updated just now", or "From your message" with no live data; null when neither applies. */
    val caption: String?,
    /** "12633 · MS → TPJ · 21:30" for the collapsed row. */
    val summary: String,
    /** The one sentence a screen reader says for the card. */
    val spoken: String
) {
    val isFinal: Boolean get() = kind.isFinal

    companion object {
        private const val NB = ' '

        fun of(s: TrainSnapshot, now: Instant, liveOn: Boolean): TrainCardModel {
            val t = s.train
            val moves = s.kind != TrainStatusKind.CANCELLED && s.kind != TrainStatusKind.DIVERTED
            val departClock = s.bestDeparture?.let { TrainText.clock(it) }
            val origin = endpoint(
                s.from, departClock, s.scheduledDeparture?.let { TrainText.clock(it) }, shift(s.scheduledDeparture, s.bestDeparture, moves)
            )
            val destination = endpoint(
                s.to, TrainText.arrivalClock(s), s.scheduledArrival?.let { TrainText.clock(it) }, shift(s.scheduledArrival, s.bestArrival, moves)
            )
            val chips = if (!moves) emptyList() else listOfNotNull(
                s.platform?.let { "Platform $it" },
                t.chips.joinToString(" · ").ifEmpty { null }
            )
            val route = if (s.from?.code != null && s.to?.code != null) "${s.from.code} → ${s.to.code}" else null
            val summary = listOfNotNull(t.number, route, if (moves) departClock else null).joinToString(" · ")
            val duration = if (s.bestDeparture != null && s.bestArrival != null) {
                Duration.between(s.bestDeparture, s.bestArrival).toMinutes().takeIf { it in 1..(72 * 60) }?.let { span(it) }
            } else null
            val caption = when {
                s.updatedAt != null -> FlightText.updated(now.toEpochMilli(), s.updatedAt)
                !liveOn -> "From your message"
                else -> null
            }
            val model = TrainCardModel(
                key = t.key, dismissKey = t.dismissKey, number = t.number, name = t.name, kind = s.kind, pill = pill(s),
                origin = origin, destination = destination, progress = TrainText.progress(s, now), showTrain = moves, duration = duration,
                supporting = supporting(s, now), next = nextLine(s), chips = chips, caption = caption, summary = summary, spoken = ""
            )
            return model.copy(spoken = spoken(model))
        }

        /** The journey still to come or under way first, the finished ones after, otherwise in the order given. */
        fun featuredFirst(models: List<TrainCardModel>): List<TrainCardModel> = models.sortedBy { it.isFinal }

        /** "1h 5m" with no break inside, so a narrow line never splits a duration. */
        fun span(minutes: Long): String = Countdown.span(minutes).replace(' ', NB)

        /** "in 1h 5m", "now", "12m ago", with no break inside the duration. */
        fun until(now: Instant, target: Instant): String = Countdown.until(now, target).replace(' ', NB)

        private fun endpoint(place: com.zyke.message.domain.flight.Place?, time: String?, scheduled: String?, shift: TimeShift): FlightEndpoint {
            val code = place?.label ?: "—"
            val city = place?.city?.takeIf { place.code != null && it.isNotBlank() && !it.equals(place.code, ignoreCase = true) }
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

        /** The pill: short enough to share a line with the train number on a small phone. */
        fun pill(s: TrainSnapshot): String = when (s.kind) {
            TrainStatusKind.RUNNING_LATE -> "Late " + FlightText.delayWords(s.delayMinutes.coerceAtLeast(1)).replace(' ', NB)
            else -> s.kind.label
        }

        private fun supporting(s: TrainSnapshot, now: Instant): String {
            val dep = s.bestDeparture
            val arrives = s.bestArrival
            return when (s.kind) {
                TrainStatusKind.CANCELLED -> "Cancelled · check with Indian Railways"
                TrainStatusKind.PARTIALLY_CANCELLED -> "Partly cancelled · check your stations"
                TrainStatusKind.DIVERTED -> "Diverted · check with Indian Railways"
                TrainStatusKind.ARRIVED -> {
                    val at = arrives?.let { TrainText.clock(it) }
                    val versus = TrainText.againstSchedule(s.scheduledArrival, s.bestArrival)
                    listOfNotNull("Arrived" + (at?.let { " $it" } ?: ""), versus).joinToString(" · ")
                }
                else -> if (!s.departed) beforeBoarding(s, now, dep) else {
                    val versus = TrainText.againstSchedule(s.scheduledArrival, s.bestArrival)
                    val here = s.position?.takeIf { it.atStation && s.kind == TrainStatusKind.AT_STATION }?.let { "At ${it.name}" }
                    when {
                        arrives != null && arrives.isAfter(now) ->
                            listOfNotNull(here ?: "Arrives " + until(now, arrives), if (here != null) "arrives " + TrainText.clock(arrives) else null, versus).joinToString(" · ")
                        arrives != null -> "Arriving now"
                        dep != null -> "Left " + TrainText.clock(dep)
                        else -> s.kind.label
                    }
                }
            }
        }

        private fun beforeBoarding(s: TrainSnapshot, now: Instant, dep: Instant?): String {
            val station = "your station"
            val reaches = s.reachesBoarding
            return when {
                s.kind == TrainStatusKind.AT_STATION -> "Standing at $station"
                s.isLive && reaches != null && reaches.isAfter(now) -> "Reaches $station " + until(now, reaches)
                s.isLive && reaches != null -> "Due at $station now"
                dep == null -> "Departure time not in your message"
                dep.isAfter(now) -> "Departs " + until(now, dep)
                else -> "Was due ${TrainText.clock(dep)} · " + until(now, dep)
            }
        }

        private fun nextLine(s: TrainSnapshot): String? {
            if (!s.isLive || s.arrived || s.kind == TrainStatusKind.CANCELLED || s.kind == TrainStatusKind.DIVERTED) return null
            if (!s.departed) {
                val p = s.position ?: return null
                val at = p.at?.let { TrainText.clock(it) }
                val late = if (p.delayMinutes >= TrainTracker.LATE_MINUTES) FlightText.delayWords(p.delayMinutes) + " late" else null
                return "Last seen at ${p.name}" + listOfNotNull(at, late).joinToString(", ").let { if (it.isEmpty()) "" else " · $it" }
            }
            val next = s.nextStop ?: return null
            val at = TrainText.expectedClock(next, s.position?.delayMinutes ?: s.delayMinutes)
            return "Next station: ${TrainText.stopName(next)}" + (at?.let { " · expected $it" } ?: "")
        }

        private fun spoken(m: TrainCardModel): String {
            fun name(e: FlightEndpoint) = e.city ?: e.code
            fun timeWords(verb: String, e: FlightEndpoint): String? = e.time?.let { t -> verb + " " + t + (e.was?.let { ", was $it" } ?: "") }
            val moves = m.kind != TrainStatusKind.CANCELLED && m.kind != TrainStatusKind.DIVERTED
            val over = m.kind == TrainStatusKind.ARRIVED
            val away = over || m.kind == TrainStatusKind.DEPARTED
            val route = if (m.origin.code != "—" && m.destination.code != "—") "${name(m.origin)} to ${name(m.destination)}" else null
            val times = if (moves) listOfNotNull(
                timeWords(if (away) "Left" else "Leaves", m.origin), timeWords(if (over) "arrived" else "arrives", m.destination)
            ).joinToString(", ").ifEmpty { null } else null
            val title = listOfNotNull(m.name, m.number).joinToString(" ")
            val parts = listOfNotNull(
                "Train $title, ${m.pill}", route, times, m.supporting, m.next, m.chips.joinToString(", ").ifEmpty { null }, m.caption
            )
            return parts.joinToString(". ").replace(NB, ' ')
        }
    }
}
