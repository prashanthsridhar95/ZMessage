package com.zyke.message.domain.flight

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Where a flight is in its day. [label] is what the status pill says (always words, never colour alone). */
enum class FlightStatusKind(val label: String) {
    SCHEDULED("Scheduled"),
    CHECK_IN("Check-in open"),
    BOARDING("Boarding"),
    DELAYED("Delayed"),
    DEPARTED("Departed"),
    IN_AIR("In the air"),
    LANDED("Landed"),
    CANCELLED("Cancelled");

    /** The flight is over for the day's purposes (no countdown, no refresh needed once the sheet shows it). */
    val isFinal: Boolean get() = this == LANDED || this == CANCELLED
}

/** An airline text as the tracker receives it: the words and when it arrived (epoch milliseconds). */
data class FlightSms(val body: String, val at: Long)

/**
 * One flight the person has booked, as far as their messages say. Built by merging every text about it ([FlightTracker.bookings]).
 * [status] here is only what messages can say (scheduled, check-in, boarding, delayed, cancelled); departed, in the air and
 * landed come from live data.
 */
data class BookedFlight(
    val airline: Airline,
    val number: String,
    /** The day of departure, on the departure airport's clock. */
    val date: LocalDate,
    val origin: Place?,
    val destination: Place?,
    val departure: LocalTime?,
    val arrival: LocalTime?,
    /** Shown to the person (and copied by them); never sent anywhere. */
    val pnr: String?,
    val terminal: String?,
    val gate: String?,
    val status: FlightStatusKind = FlightStatusKind.SCHEDULED,
    val delayMinutes: Int? = null,
    val newDeparture: LocalTime? = null,
    /** Epoch milliseconds of the newest message about it. */
    val lastMessageAt: Long = 0L
) {
    /** "6E 2345". */
    val code: String get() = "${airline.code} $number"

    /** The same flight on the same day, whichever message told it. */
    val key: String get() = "${airline.code}$number|$date"

    /** Dismissing the card is remembered under this; the day is in it, so tomorrow's flight is never hidden by it. */
    val dismissKey: String get() = "flight-${airline.code}$number-$date"

    fun departureZone(fallback: ZoneId): ZoneId = Airports.zone(origin?.code) ?: fallback
    fun arrivalZone(fallback: ZoneId): ZoneId = Airports.zone(destination?.code) ?: fallback
}

/** What a live source said about one flight on one day. Anything it did not say is null. */
data class LiveStatus(
    val kind: FlightStatusKind,
    val scheduledDeparture: Instant? = null,
    val estimatedDeparture: Instant? = null,
    val actualDeparture: Instant? = null,
    val scheduledArrival: Instant? = null,
    val estimatedArrival: Instant? = null,
    val actualArrival: Instant? = null,
    val originCode: String? = null,
    val destinationCode: String? = null,
    val terminal: String? = null,
    val gate: String? = null,
    val arrivalTerminal: String? = null,
    val baggage: String? = null,
    /** Minutes later than scheduled (0 when on time or early). */
    val delayMinutes: Int = 0,
    /** Epoch milliseconds of the fetch that produced this. */
    val updatedAt: Long = 0L
)

/** The one thing the card and the sheet draw: the booking and the live data merged. */
data class FlightSnapshot(
    val flight: BookedFlight,
    val kind: FlightStatusKind,
    val delayMinutes: Int,
    val origin: Place?,
    val destination: Place?,
    val scheduledDeparture: Instant?,
    /** Actual, else estimated, else the new time from a message, else the schedule. */
    val bestDeparture: Instant?,
    val scheduledArrival: Instant?,
    val bestArrival: Instant?,
    val departureZone: ZoneId,
    val arrivalZone: ZoneId,
    val terminal: String?,
    val gate: String?,
    val arrivalTerminal: String?,
    val baggage: String?,
    /** True when [kind] and the times come from the live source, false when they are only from the person's messages. */
    val isLive: Boolean,
    /** Epoch milliseconds of the live data shown, or null when there is none. */
    val updatedAt: Long?
) {
    val code: String get() = flight.code
}

/** "in 2h 10m", "in 45m", "now", "12m ago". */
object Countdown {
    fun until(now: Instant, target: Instant): String {
        val minutes = Duration.between(now, target).toMinutes()
        return when {
            minutes > 0 -> "in " + span(minutes)
            minutes == 0L && !target.isBefore(now) -> "now"
            else -> {
                val ago = Duration.between(target, now).toMinutes()
                if (ago <= 0) "now" else span(ago) + " ago"
            }
        }
    }

    /** "2h 10m", "2h", "45m", "1d 3h". */
    fun span(totalMinutes: Long): String {
        val m = totalMinutes.coerceAtLeast(0)
        val days = m / (24 * 60)
        val hours = m % (24 * 60) / 60
        val minutes = m % 60
        return when {
            days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
            hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            else -> "${minutes}m"
        }
    }
}

object FlightTracker {
    private const val RETAIN_AFTER_LANDING_HOURS = 3L
    private const val UNKNOWN_FLIGHT_HOURS = 3L
    /** A delay shorter than this is on time for the status word. */
    const val DELAY_THRESHOLD_MINUTES = 10
    private const val LOOKBACK_DAYS = 400L

    private class Draft(val first: FlightFacts, val date: LocalDate) {
        var origin: Place? = null
        var destination: Place? = null
        var departure: LocalTime? = null
        var arrival: LocalTime? = null
        var pnr: String? = null
        var terminal: String? = null
        var gate: String? = null
        var status = FlightStatusKind.SCHEDULED
        var delay: Int? = null
        var newDeparture: LocalTime? = null
        var lastAt = 0L
    }

    /**
     * Every flight the messages talk about, one per airline, number and day. Texts are read oldest first, so a later
     * delay, gate or cancellation text wins over the booking. A text without a date (check-in, boarding, gate) belongs to
     * the flight of that number nearest the day it arrived.
     */
    fun bookings(messages: List<FlightSms>, now: Instant, zone: ZoneId): List<BookedFlight> {
        val earliest = now.minus(Duration.ofDays(LOOKBACK_DAYS)).toEpochMilli()
        val drafts = LinkedHashMap<String, Draft>()
        messages.asSequence().filter { it.at >= earliest }.sortedBy { it.at }.forEach { sms ->
            val received = Instant.ofEpochMilli(sms.at).atZone(zone).toLocalDate()
            val facts = FlightParser.parse(sms.body, received) ?: return@forEach
            val date = facts.date ?: nearestDate(drafts.values, facts, received) ?: received
            val key = "${facts.airline.code}${facts.number}|$date"
            val draft = drafts.getOrPut(key) { Draft(facts, date) }
            apply(draft, facts, sms.at)
        }
        return drafts.values.map { d ->
            BookedFlight(
                d.first.airline, d.first.number, d.date, d.origin, d.destination, d.departure, d.arrival, d.pnr, d.terminal, d.gate,
                d.status, d.delay, d.newDeparture, d.lastAt
            )
        }
    }

    private fun nearestDate(drafts: Collection<Draft>, facts: FlightFacts, received: LocalDate): LocalDate? =
        drafts.filter { it.first.airline == facts.airline && it.first.number == facts.number }
            .map { it.date }
            .filter { kotlin.math.abs(it.toEpochDay() - received.toEpochDay()) <= 1 }
            .minByOrNull { kotlin.math.abs(it.toEpochDay() - received.toEpochDay()) }

    private fun apply(d: Draft, f: FlightFacts, at: Long) {
        f.origin?.let { d.origin = it }
        f.destination?.let { d.destination = it }
        f.arrival?.let { d.arrival = it }
        f.pnr?.let { d.pnr = it }
        f.terminal?.let { d.terminal = it }
        f.gate?.let { d.gate = it }
        f.departure?.let { if (f.event != FlightEvent.DELAYED || d.departure == null) d.departure = it }
        d.lastAt = maxOf(d.lastAt, at)
        when (f.event) {
            FlightEvent.CANCELLED -> d.status = FlightStatusKind.CANCELLED
            FlightEvent.DELAYED -> {
                d.status = FlightStatusKind.DELAYED
                d.newDeparture = f.newDeparture ?: d.newDeparture
                d.delay = f.delayMinutes ?: minutesBetween(d.departure, f.newDeparture) ?: d.delay
            }
            FlightEvent.RESCHEDULED -> {
                // A new schedule replaces the old one; it is no longer "a delay" against it.
                f.newDeparture?.let { d.departure = it }
                d.newDeparture = null
                d.delay = null
                d.status = FlightStatusKind.SCHEDULED
            }
            FlightEvent.BOARDING -> d.status = FlightStatusKind.BOARDING
            FlightEvent.CHECK_IN -> if (d.status != FlightStatusKind.BOARDING) d.status = FlightStatusKind.CHECK_IN
            FlightEvent.GATE_CHANGE -> Unit
            FlightEvent.BOOKED, FlightEvent.REMINDER ->
                // A booking or reminder that comes after a cancellation is a rebooking; otherwise it does not undo check-in or a delay.
                if (d.status == FlightStatusKind.CANCELLED && f.event == FlightEvent.BOOKED) d.status = FlightStatusKind.SCHEDULED
        }
    }

    private fun minutesBetween(from: LocalTime?, to: LocalTime?): Int? {
        if (from == null || to == null) return null
        var m = Duration.between(from, to).toMinutes().toInt()
        if (m < 0) m += 24 * 60
        return m.takeIf { it in 1..(12 * 60) }
    }

    // ---- which flights are today's

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Instant = date.atTime(time).atZone(zone).toInstant()

    /** The scheduled departure instant (the airport's clock), or null when the messages gave no time. */
    fun scheduledDeparture(f: BookedFlight, zone: ZoneId): Instant? = f.departure?.let { at(f.date, it, f.departureZone(zone)) }

    /** The scheduled landing: on the destination's clock, on the departure day or the day after for a late flight. */
    fun scheduledArrival(f: BookedFlight, zone: ZoneId): Instant? {
        val arrival = f.arrival ?: return null
        val departs = scheduledDeparture(f, zone)
        var instant = at(f.date, arrival, f.arrivalZone(f.departureZone(zone)))
        if (departs != null) while (!instant.isAfter(departs)) instant = instant.plus(Duration.ofDays(1))
        return instant
    }

    private fun messageDeparture(f: BookedFlight, zone: ZoneId): Instant? {
        val planned = scheduledDeparture(f, zone) ?: return null
        f.newDeparture?.let { n ->
            var i = at(f.date, n, f.departureZone(zone))
            // A delay past midnight.
            if (i.isBefore(planned.minus(Duration.ofHours(1)))) i = i.plus(Duration.ofDays(1))
            return i
        }
        return f.delayMinutes?.let { planned.plus(Duration.ofMinutes(it.toLong())) } ?: planned
    }

    /**
     * The flights to show right now: from the start of the day of departure (on the departure airport's clock) until a few hours
     * after landing, so an overnight flight that left yesterday is still here until it lands, and a day-before or day-after
     * flight is not. A flight whose texts gave no time stays for the whole day; a cancelled one too. Earliest first.
     */
    fun today(flights: List<BookedFlight>, now: Instant, zone: ZoneId, live: Map<String, LiveStatus> = emptyMap()): List<BookedFlight> =
        flights.filter { isToday(it, now, zone, live[it.key]) }
            .sortedBy { (live[it.key]?.let { l -> l.actualDeparture ?: l.estimatedDeparture } ?: messageDeparture(it, zone)) ?: Instant.MAX }

    fun isToday(f: BookedFlight, now: Instant, zone: ZoneId, live: LiveStatus? = null): Boolean {
        val depZone = f.departureZone(zone)
        val dayStart = f.date.atStartOfDay(depZone).toInstant()
        val dayEnd = f.date.plusDays(1).atStartOfDay(depZone).toInstant()
        if (now.isBefore(dayStart)) return false
        if (f.status == FlightStatusKind.CANCELLED || live?.kind == FlightStatusKind.CANCELLED) return now.isBefore(dayEnd)
        val departs = live?.actualDeparture ?: live?.estimatedDeparture ?: messageDeparture(f, zone)
        val lands = live?.actualArrival ?: live?.estimatedArrival
            ?: scheduledArrival(f, zone)?.let { a -> a.plus(Duration.ofMinutes(departureShiftMinutes(f, zone).toLong())) }
            ?: departs?.plus(Duration.ofHours(UNKNOWN_FLIGHT_HOURS))
        val end = lands?.plus(Duration.ofHours(RETAIN_AFTER_LANDING_HOURS)) ?: dayEnd
        // Never gone before the end of the departure day for a flight with no known times.
        return !now.isAfter(end) && (lands != null || now.isBefore(dayEnd))
    }

    private fun departureShiftMinutes(f: BookedFlight, zone: ZoneId): Long {
        val planned = scheduledDeparture(f, zone) ?: return 0L
        val moved = messageDeparture(f, zone) ?: return 0L
        return Duration.between(planned, moved).toMinutes().coerceAtLeast(0)
    }

    // ---- merging the booking with live data

    fun snapshot(f: BookedFlight, live: LiveStatus?, zone: ZoneId): FlightSnapshot {
        val depZone = f.departureZone(zone)
        val arrZone = f.arrivalZone(depZone)
        val plannedDeparture = scheduledDeparture(f, zone)
        val plannedArrival = scheduledArrival(f, zone)
        val fromMessages = messageDeparture(f, zone)
        val shift = Duration.between(plannedDeparture ?: fromMessages ?: Instant.EPOCH, fromMessages ?: plannedDeparture ?: Instant.EPOCH).toMinutes().toInt()
        if (live == null) {
            val delay = (f.delayMinutes ?: shift).coerceAtLeast(0)
            val kind = if (f.status == FlightStatusKind.DELAYED && delay < 1) FlightStatusKind.SCHEDULED else f.status
            return FlightSnapshot(
                f, kind, if (kind == FlightStatusKind.DELAYED) delay else 0, f.origin, f.destination,
                plannedDeparture, fromMessages, plannedArrival, plannedArrival?.plus(Duration.ofMinutes(shift.coerceAtLeast(0).toLong())),
                depZone, arrZone, f.terminal, f.gate, null, null, isLive = false, updatedAt = null
            )
        }
        // A booking text can know check-in or boarding, which a flight tracker does not; live data wins for everything else.
        val kind = when {
            live.kind == FlightStatusKind.SCHEDULED && f.status in setOf(FlightStatusKind.CHECK_IN, FlightStatusKind.BOARDING) -> f.status
            live.kind == FlightStatusKind.SCHEDULED && f.status == FlightStatusKind.CANCELLED -> FlightStatusKind.CANCELLED
            else -> live.kind
        }
        val origin = f.origin ?: live.originCode?.let { Place(it, Airports.city(it)) }
        val destination = f.destination ?: live.destinationCode?.let { Place(it, Airports.city(it)) }
        val bestDeparture = live.actualDeparture ?: live.estimatedDeparture ?: fromMessages ?: live.scheduledDeparture ?: plannedDeparture
        val bestArrival = live.actualArrival ?: live.estimatedArrival ?: live.scheduledArrival ?: plannedArrival
        return FlightSnapshot(
            f, kind, if (kind == FlightStatusKind.DELAYED) live.delayMinutes.coerceAtLeast(1) else 0,
            origin, destination, live.scheduledDeparture ?: plannedDeparture, bestDeparture, live.scheduledArrival ?: plannedArrival, bestArrival,
            Airports.zone(origin?.code) ?: depZone, Airports.zone(destination?.code) ?: (Airports.zone(origin?.code) ?: depZone),
            live.terminal ?: f.terminal, live.gate ?: f.gate, live.arrivalTerminal, live.baggage,
            isLive = true, updatedAt = live.updatedAt.takeIf { it > 0 }
        )
    }
}
