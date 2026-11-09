package com.zyke.message.domain.train

import com.zyke.message.domain.flight.FlightText
import com.zyke.message.domain.flight.Place
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Where a train is, for the person boarding it. [label] is the short status word; the card adds detail ("Late 25 min"). */
enum class TrainStatusKind(val label: String) {
    SCHEDULED("Not started"),
    ON_TIME("On time"),
    RUNNING_LATE("Running late"),
    AT_STATION("At station"),
    DEPARTED("Departed"),
    ARRIVED("Arrived"),
    CANCELLED("Cancelled"),
    PARTIALLY_CANCELLED("Partly cancelled"),
    DIVERTED("Diverted");

    /** Nothing more will change for the day's purposes (no countdown, no refresh needed). */
    val isFinal: Boolean get() = this == ARRIVED || this == CANCELLED || this == DIVERTED
}

/** A railway text as the tracker receives it: the words and when it arrived (epoch milliseconds). */
data class TrainSms(val body: String, val at: Long)

/**
 * One journey the person has booked, as far as their messages say. Built by merging every text about it ([TrainTracker.bookings])
 * or typed in by hand. All times are on the Indian clock ([Stations.ZONE]); [date] is the day of journey from the boarding
 * station. [pnr] is shown to the person (and copied by them) and never sent anywhere. [status] is only what messages can say.
 */
data class BookedTrain(
    val number: String,
    val name: String?,
    val date: LocalDate,
    /** The station the person boards at (may be mid-route). */
    val from: Place?,
    /** The station the person gets off at. */
    val to: Place?,
    val departure: LocalTime?,
    val arrival: LocalTime?,
    /** Days after [date] the scheduled arrival falls, when known (a multi-day journey); 0 means "work it out from the times". */
    val arrivalDayOffset: Int = 0,
    val trainClass: String? = null,
    val coach: String? = null,
    /** "32 SU" or "32". */
    val berth: String? = null,
    /** "CNF", "RAC 12", "WL 5" after the chart or booking, when a text said. */
    val ticket: String? = null,
    val pnr: String? = null,
    val platform: String? = null,
    val status: TrainStatusKind = TrainStatusKind.SCHEDULED,
    val delayMinutes: Int? = null,
    val newDeparture: LocalTime? = null,
    val chartPrepared: Boolean = false,
    /** The person's own ticket was cancelled: the journey is no longer theirs. */
    val ticketCancelled: Boolean = false,
    /** Which day of the train's run the boarding station is reached on (1 = the day it starts); learned from live data. */
    val boardingDay: Int = 1,
    val lastMessageAt: Long = 0L
) {
    /** The same journey, whichever message told it. */
    val key: String get() = "$number|$date"
    val dismissKey: String get() = "train-$number-$date"

    /** "12633", or "12633 Kanniyakumari Exp". */
    val title: String get() = listOfNotNull(number, name).joinToString(" ")

    /** Class, then coach and berth (or the waiting/RAC status): the small labels. Only the ones that are known. */
    val chips: List<String>
        get() = listOfNotNull(
            trainClass,
            when {
                coach != null && berth != null -> "$coach · $berth"
                coach != null -> coach
                ticket != null && !ticket.startsWith("CNF") -> ticket
                berth != null -> berth
                else -> null
            }
        )
}

/** One stop on the train's run, with the times the live source gave. Times are instants on the Indian clock. */
data class TrainStop(
    val code: String,
    val name: String,
    /** 1 = the day the train starts, 2 = the next day, and so on. */
    val day: Int = 1,
    val scheduledArrival: Instant? = null,
    val scheduledDeparture: Instant? = null,
    val expectedArrival: Instant? = null,
    val expectedDeparture: Instant? = null,
    val actualArrival: Instant? = null,
    val actualDeparture: Instant? = null,
    val platform: String? = null
)

/** What a live source said about one run of one train. Anything it did not say is null or empty. */
data class TrainLive(
    val trainName: String? = null,
    val stops: List<TrainStop> = emptyList(),
    /** The last station the train was seen at (code), and when. */
    val lastStationCode: String? = null,
    val lastStationName: String? = null,
    val lastSeenAt: Instant? = null,
    /** Minutes late (negative = early) as of the last station. */
    val delayMinutes: Int = 0,
    /** Standing at [lastStationCode] right now (not yet left it). */
    val atStation: Boolean = false,
    /** The run has begun (the train has left its first station). */
    val started: Boolean = false,
    val cancelled: Boolean = false,
    val partiallyCancelled: Boolean = false,
    val diverted: Boolean = false,
    /** The source says the train has reached its last station. */
    val terminated: Boolean = false,
    /** Which run day the person's boarding station is on (from the route), so the next ask can name the right start date. */
    val boardingDay: Int = 1,
    /** Epoch milliseconds of the fetch that produced this. */
    val updatedAt: Long = 0L
)

data class TrainPosition(val code: String?, val name: String, val at: Instant?, val delayMinutes: Int, val atStation: Boolean)

/** The one thing the card and the sheet draw: the booking and the live data merged, from the person's boarding station. */
data class TrainSnapshot(
    val train: BookedTrain,
    val kind: TrainStatusKind,
    /** Minutes late (negative early): at the boarding station until the train has left it, then where it is now. */
    val delayMinutes: Int,
    val from: Place?,
    val to: Place?,
    val scheduledDeparture: Instant?,
    /** Actual, else expected, else the new time from a message, else the schedule: at the boarding station. */
    val bestDeparture: Instant?,
    val scheduledArrival: Instant?,
    val bestArrival: Instant?,
    /** The platform at the boarding station until the train has left it, then at the destination; null when not known. */
    val platform: String?,
    /** The stops still ahead of the train, up to and including the destination. */
    val upcoming: List<TrainStop>,
    val position: TrainPosition?,
    val departed: Boolean,
    val arrived: Boolean,
    /** When the train is expected at the person's boarding station (null once it has left, or when not known). */
    val reachesBoarding: Instant?,
    val isLive: Boolean,
    val updatedAt: Long?
) {
    val name: String? get() = train.name
    val nextStop: TrainStop? get() = upcoming.firstOrNull()
}

object TrainTracker {
    private val ZONE = Stations.ZONE
    private const val RETAIN_AFTER_ARRIVAL_HOURS = 3L
    private const val SHOW_BEFORE_EARLY_DEPARTURE_HOURS = 6L
    private const val UNKNOWN_JOURNEY_HOURS = 12L
    /** At or past this many minutes a train is "running late" (and "early") rather than on time. */
    const val LATE_MINUTES = 5
    private const val LOOKBACK_DAYS = 400L

    private class Draft(val first: TrainFacts, val date: LocalDate) {
        var name: String? = null
        var from: Place? = null
        var to: Place? = null
        var departure: LocalTime? = null
        var arrival: LocalTime? = null
        var cls: String? = null
        var coach: String? = null
        var berth: String? = null
        var ticket: String? = null
        var pnr: String? = null
        var platform: String? = null
        var status = TrainStatusKind.SCHEDULED
        var delay: Int? = null
        var newDeparture: LocalTime? = null
        var chart = false
        var ticketCancelled = false
        var lastAt = 0L
    }

    /**
     * Every journey the messages talk about, one per train number and day of journey. Texts are read oldest first, so a later
     * chart, platform, delay or cancellation text wins over the booking. A text without a date belongs to the journey of that
     * train nearest the day it arrived.
     */
    fun bookings(messages: List<TrainSms>, now: Instant): List<BookedTrain> {
        val earliest = now.minus(Duration.ofDays(LOOKBACK_DAYS)).toEpochMilli()
        val drafts = LinkedHashMap<String, Draft>()
        messages.asSequence().filter { it.at >= earliest }.sortedBy { it.at }.forEach { sms ->
            val received = Instant.ofEpochMilli(sms.at).atZone(ZONE).toLocalDate()
            val facts = TrainParser.parse(sms.body, received) ?: return@forEach
            val date = facts.date ?: nearestDate(drafts.values, facts, received) ?: received
            val draft = drafts.getOrPut("${facts.number}|$date") { Draft(facts, date) }
            apply(draft, facts, sms.at)
        }
        return drafts.values.map { d ->
            BookedTrain(
                d.first.number, d.name, d.date, d.from, d.to, d.departure, d.arrival, 0, d.cls, d.coach, d.berth, d.ticket, d.pnr, d.platform,
                d.status, d.delay, d.newDeparture, d.chart, d.ticketCancelled, 1, d.lastAt
            )
        }
    }

    private fun nearestDate(drafts: Collection<Draft>, facts: TrainFacts, received: LocalDate): LocalDate? =
        drafts.filter { it.first.number == facts.number }.map { it.date }
            .filter { kotlin.math.abs(it.toEpochDay() - received.toEpochDay()) <= 1 }
            .minByOrNull { kotlin.math.abs(it.toEpochDay() - received.toEpochDay()) }

    private fun apply(d: Draft, f: TrainFacts, at: Long) {
        f.name?.let { d.name = it }
        f.from?.let { d.from = it }
        f.to?.let { d.to = it }
        f.arrival?.let { d.arrival = it }
        f.trainClass?.let { d.cls = it }
        f.coach?.let { d.coach = it }
        f.berth?.let { d.berth = it }
        f.ticket?.let { d.ticket = it }
        f.pnr?.let { d.pnr = it }
        f.platform?.let { d.platform = it }
        f.departure?.let { if (f.event != TrainEvent.DELAYED || d.departure == null) d.departure = it }
        d.lastAt = maxOf(d.lastAt, at)
        when (f.event) {
            TrainEvent.CANCELLED -> d.status = TrainStatusKind.CANCELLED
            TrainEvent.PARTIALLY_CANCELLED -> d.status = TrainStatusKind.PARTIALLY_CANCELLED
            TrainEvent.TICKET_CANCELLED -> d.ticketCancelled = true
            TrainEvent.DELAYED -> {
                d.status = TrainStatusKind.RUNNING_LATE
                d.newDeparture = f.newDeparture ?: d.newDeparture
                d.delay = f.delayMinutes ?: minutesBetween(d.departure, f.newDeparture) ?: d.delay
            }
            TrainEvent.RESCHEDULED -> {
                f.newDeparture?.let { d.departure = it }
                d.newDeparture = null
                d.delay = null
                d.status = TrainStatusKind.SCHEDULED
            }
            TrainEvent.CHART -> d.chart = true
            TrainEvent.BOOKED -> {
                // A booking after a cancellation is a rebooking.
                if (d.ticketCancelled) d.ticketCancelled = false
                if (d.status == TrainStatusKind.CANCELLED || d.status == TrainStatusKind.PARTIALLY_CANCELLED) d.status = TrainStatusKind.SCHEDULED
            }
            TrainEvent.PLATFORM, TrainEvent.REMINDER -> Unit
        }
    }

    private fun minutesBetween(from: LocalTime?, to: LocalTime?): Int? {
        if (from == null || to == null) return null
        var m = Duration.between(from, to).toMinutes().toInt()
        if (m < 0) m += 24 * 60
        return m.takeIf { it in 1..(24 * 60) }
    }

    // ---- the schedule from messages

    fun scheduledDeparture(t: BookedTrain): Instant? = t.departure?.let { t.date.atTime(it).atZone(ZONE).toInstant() }

    /** The scheduled arrival: [BookedTrain.arrivalDayOffset] days after the day of journey when known, else the first such time after departure. */
    fun scheduledArrival(t: BookedTrain): Instant? {
        val arrival = t.arrival ?: return null
        if (t.arrivalDayOffset > 0) return t.date.plusDays(t.arrivalDayOffset.toLong()).atTime(arrival).atZone(ZONE).toInstant()
        val departs = scheduledDeparture(t)
        var instant = t.date.atTime(arrival).atZone(ZONE).toInstant()
        if (departs != null) while (!instant.isAfter(departs)) instant = instant.plus(Duration.ofDays(1))
        return instant
    }

    private fun messageDeparture(t: BookedTrain): Instant? {
        val planned = scheduledDeparture(t) ?: return null
        t.newDeparture?.let { n ->
            var i = t.date.atTime(n).atZone(ZONE).toInstant()
            if (i.isBefore(planned.minus(Duration.ofHours(1)))) i = i.plus(Duration.ofDays(1))
            return i
        }
        return t.delayMinutes?.let { planned.plus(Duration.ofMinutes(it.toLong())) } ?: planned
    }

    // ---- which journeys are today's

    /**
     * The journeys to show right now: from the start of the day of journey (or six hours before a train that leaves in the small
     * hours) until a few hours after arrival, so an overnight journey stays until it ends and yesterday's or tomorrow's does not. A
     * journey with no times stays for its whole day; a cancelled one too; one whose ticket was cancelled never shows. Earliest first.
     */
    fun today(trains: List<BookedTrain>, now: Instant, live: Map<String, TrainLive> = emptyMap()): List<BookedTrain> =
        trains.filter { isToday(it, now, live[it.key]) }
            .sortedBy { snapshot(it, live[it.key], now).bestDeparture ?: Instant.MAX }

    fun isToday(t: BookedTrain, now: Instant, live: TrainLive? = null): Boolean {
        if (t.ticketCancelled) return false
        val dayStart = t.date.atStartOfDay(ZONE).toInstant()
        val dayEnd = t.date.plusDays(1).atStartOfDay(ZONE).toInstant()
        val planned = scheduledDeparture(t)
        val start = if (planned != null && planned.minus(Duration.ofHours(SHOW_BEFORE_EARLY_DEPARTURE_HOURS)).isBefore(dayStart)) planned.minus(Duration.ofHours(SHOW_BEFORE_EARLY_DEPARTURE_HOURS)) else dayStart
        if (now.isBefore(start)) return false
        val s = snapshot(t, live, now)
        if (s.kind == TrainStatusKind.CANCELLED) return now.isBefore(dayEnd)
        val ends = s.bestArrival ?: s.bestDeparture?.plus(Duration.ofHours(UNKNOWN_JOURNEY_HOURS))
        val end = ends?.plus(Duration.ofHours(RETAIN_AFTER_ARRIVAL_HOURS)) ?: dayEnd
        return !now.isAfter(end) && (ends != null || now.isBefore(dayEnd))
    }

    // ---- merging the booking with live data

    private fun stopIndex(stops: List<TrainStop>, place: Place?): Int {
        if (place == null) return -1
        place.code?.let { c -> stops.indexOfFirst { it.code.equals(c, ignoreCase = true) }.let { if (it >= 0) return it } }
        return stops.indexOfFirst { it.name.equals(place.city, ignoreCase = true) }
    }

    private fun minutes(from: Instant?, to: Instant?): Int? = if (from == null || to == null) null else Duration.between(from, to).toMinutes().toInt()

    fun snapshot(t: BookedTrain, live: TrainLive?, now: Instant): TrainSnapshot {
        val plannedDep = scheduledDeparture(t)
        val plannedArr = scheduledArrival(t)
        val fromMessages = messageDeparture(t)
        val shift = minutes(plannedDep, fromMessages) ?: 0
        if (live == null) {
            val delay = (t.delayMinutes ?: shift).coerceAtLeast(0)
            val kind = when {
                t.status == TrainStatusKind.CANCELLED || t.status == TrainStatusKind.PARTIALLY_CANCELLED -> t.status
                t.status == TrainStatusKind.RUNNING_LATE && delay >= LATE_MINUTES -> TrainStatusKind.RUNNING_LATE
                else -> TrainStatusKind.SCHEDULED
            }
            return TrainSnapshot(
                t, kind, if (kind == TrainStatusKind.RUNNING_LATE) delay else 0, t.from, t.to, plannedDep, fromMessages, plannedArr,
                plannedArr?.plus(Duration.ofMinutes(shift.coerceAtLeast(0).toLong())), t.platform, emptyList(), null,
                departed = false, arrived = false, reachesBoarding = null, isLive = false, updatedAt = null
            )
        }
        val stops = live.stops
        val last = stops.lastIndex
        var boardIdx = stopIndex(stops, t.from)
        var destIdx = stopIndex(stops, t.to)
        if (boardIdx < 0) boardIdx = if (t.from == null && stops.isNotEmpty()) 0 else -1
        if (destIdx < 0) destIdx = if (t.to == null && stops.isNotEmpty()) last else -1
        val posIdx = live.lastStationCode?.let { c -> stops.indexOfFirst { it.code.equals(c, ignoreCase = true) } } ?: -1
        val boardStop = stops.getOrNull(boardIdx)
        val destStop = stops.getOrNull(destIdx)
        val late = live.delayMinutes
        fun depOf(s: TrainStop?): Instant? = s?.actualDeparture ?: s?.expectedDeparture ?: s?.scheduledDeparture?.plusMinutes(late)
        fun arrOf(s: TrainStop?): Instant? = s?.actualArrival ?: s?.expectedArrival ?: s?.scheduledArrival?.plusMinutes(late)

        val arrived = !live.cancelled && (destStop?.actualArrival != null || (destIdx >= 0 && posIdx >= destIdx && !live.cancelled) ||
            (live.terminated && (destIdx < 0 || destIdx == last)))
        val departed = !arrived && (boardStop?.actualDeparture != null ||
            (boardIdx >= 0 && (posIdx > boardIdx || (posIdx == boardIdx && !live.atStation && live.started))) ||
            (boardIdx < 0 && live.started && !(live.atStation && posIdx <= 0)))
        val departure = depOf(boardStop) ?: fromMessages?.takeIf { t.delayMinutes != null || t.newDeparture != null } ?: plannedDep?.plusMinutes(if (live.started) late else 0)
        val arrival = arrOf(destStop) ?: plannedArr?.plusMinutes(late)
        val schedDep = plannedDep ?: boardStop?.scheduledDeparture
        val schedArr = destStop?.scheduledArrival ?: plannedArr
        val delayAtBoarding = minutes(schedDep ?: boardStop?.scheduledDeparture, departure) ?: late
        val delay = if (departed || arrived) late else if (live.started || boardStop != null) delayAtBoarding else (t.delayMinutes ?: 0)
        val kind = when {
            live.cancelled -> TrainStatusKind.CANCELLED
            live.partiallyCancelled -> TrainStatusKind.PARTIALLY_CANCELLED
            live.diverted -> TrainStatusKind.DIVERTED
            arrived -> TrainStatusKind.ARRIVED
            live.atStation && posIdx >= 0 -> TrainStatusKind.AT_STATION
            delay >= LATE_MINUTES -> TrainStatusKind.RUNNING_LATE
            departed -> TrainStatusKind.DEPARTED
            live.started -> TrainStatusKind.ON_TIME
            t.status == TrainStatusKind.CANCELLED -> TrainStatusKind.CANCELLED
            else -> TrainStatusKind.SCHEDULED
        }
        val from = t.from ?: boardStop?.let { Place(it.code, it.name) }
        val to = t.to ?: destStop?.let { Place(it.code, it.name) }
        val firstAhead = (posIdx + 1).coerceAtLeast(0)
        val endAhead = if (destIdx >= 0) destIdx else last
        val upcoming = if (arrived) emptyList() else stops.filterIndexed { i, _ -> i in firstAhead..endAhead }
        val position = if (posIdx >= 0) TrainPosition(stops[posIdx].code, stops[posIdx].name, live.lastSeenAt, late, live.atStation)
        else live.lastStationName?.let { TrainPosition(live.lastStationCode, it, live.lastSeenAt, late, live.atStation) }
        val reaches = if (departed || arrived || boardStop == null) null else arrOf(boardStop) ?: departure
        val platform = if (departed || arrived) (destStop?.platform ?: t.platform.takeIf { arrived }) else (boardStop?.platform ?: t.platform)
        return TrainSnapshot(
            t, kind, delay, from, to, schedDep, departure, schedArr, arrival, platform, upcoming, position, departed, arrived, reaches,
            isLive = true, updatedAt = live.updatedAt.takeIf { it > 0 }
        )
    }

    private fun Instant.plusMinutes(m: Int): Instant = plus(Duration.ofMinutes(m.toLong()))
}

/** The words of the card, the sheet and the notifications, in one place so they are tested. All times are on the Indian clock, 24-hour. */
object TrainText {
    private val HOURS_MINUTES = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    fun clock(instant: Instant): String = instant.atZone(Stations.ZONE).format(HOURS_MINUTES)

    /** "1 h 5 min", "25 min" (shared with the flight words). */
    fun minutesWords(minutes: Int): String = FlightText.delayWords(minutes)

    /** The arrival clock with " +1" (or " +2") when it is on a later day than the departure. */
    fun arrivalClock(s: TrainSnapshot): String? {
        val arrives = s.bestArrival ?: return null
        val day = (s.bestDeparture ?: TrainTracker.scheduledDeparture(s.train))?.atZone(Stations.ZONE)?.toLocalDate() ?: s.train.date
        val extra = arrives.atZone(Stations.ZONE).toLocalDate().toEpochDay() - day.toEpochDay()
        return clock(arrives) + if (extra > 0) " +$extra" else ""
    }

    /** "12 min late", "on time", "6 min early": [ON_TIME_MINUTES] either way is on time. Null when the schedule or the time is not known. */
    fun againstSchedule(scheduled: Instant?, actual: Instant?): String? {
        if (scheduled == null || actual == null) return null
        return againstMinutes(Duration.between(scheduled, actual).toMinutes().toInt())
    }

    const val ON_TIME_MINUTES = 5

    fun againstMinutes(minutes: Int): String = when {
        kotlin.math.abs(minutes) < ON_TIME_MINUTES -> "on time"
        minutes < 0 -> minutesWords(-minutes) + " early"
        else -> minutesWords(minutes) + " late"
    }

    /** How far along the journey is, 0..1, from the person's boarding station to their destination. */
    fun progress(s: TrainSnapshot, now: Instant): Float {
        if (s.arrived) return 1f
        if (s.kind == TrainStatusKind.CANCELLED || s.kind == TrainStatusKind.DIVERTED || !s.departed) return 0f
        val dep = s.bestDeparture ?: return 0.5f
        val arr = s.bestArrival ?: return 0.5f
        val total = Duration.between(dep, arr).toMillis().coerceAtLeast(1)
        val done = Duration.between(dep, now).toMillis()
        return (done.toFloat() / total).coerceIn(0.03f, 0.97f)
    }

    /** "Villupuram" or "VM" when no name is known. */
    fun stopName(s: TrainStop): String = s.name.ifBlank { s.code }

    /** The time a stop is expected at (arrival, or departure for the first stop), "14:10"; null when not known. */
    fun expectedClock(s: TrainStop, delayMinutes: Int): String? {
        val at = s.actualArrival ?: s.expectedArrival ?: s.scheduledArrival?.plus(Duration.ofMinutes(delayMinutes.toLong()))
            ?: s.actualDeparture ?: s.expectedDeparture ?: s.scheduledDeparture?.plus(Duration.ofMinutes(delayMinutes.toLong()))
        return at?.let { clock(it) }
    }
}
