package com.zyke.message.domain.flight

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Where one flight is, as far as the in-flight notification is concerned. */
enum class TrackPhase {
    /** Not time yet (or no departure time to go by): nothing is shown. */
    WAITING,
    /** Within [FlightTracking.START_LEAD_MINUTES] of leaving: watched, with a "Departs 14:35" notice. */
    DEPARTING,
    /** Departed or in the air: the ongoing notification with the progress bar. */
    IN_AIR,
    LANDED,
    CANCELLED,
    DIVERTED,
    /** The planned landing and the cut-off after it have passed without a landing being seen. */
    GAVE_UP,
    /** The person pressed "Stop tracking" for this flight. */
    STOPPED;

    /** The flight needs the service to watch it now. */
    val isActive: Boolean get() = this == DEPARTING || this == IN_AIR

    /** The flight has finished one way or another and gets (at most) one dismissible notice. */
    val isOver: Boolean get() = this == LANDED || this == CANCELLED || this == DIVERTED || this == GAVE_UP
}

data class TrackDecision(
    val flight: BookedFlight,
    val snapshot: FlightSnapshot,
    val phase: TrackPhase,
    /** A landing worked out from the arrival time and a later answer, not reported by the source. */
    val landingEstimated: Boolean = false
) {
    val key: String get() = flight.key
}

/**
 * Which flights the live-tracking service watches, and when it stops. Pure: the service, the alarm and the app coming to the
 * front all ask the same questions of the same clock.
 *
 * Live tracking is only for a flight that has been seen to leave (or whose time has come with nothing contradicting it): it
 * needs live status switched on, and without it the person gets an ordinary reminder instead (see [FlightNotificationText.reminder]).
 */
object FlightTracking {
    /** Seconds between asking again while the service runs. */
    const val POLL_MS = 30_000L
    /** After [MAX_FAILURES] answers in a row that could not be got, ask this rarely until one works. */
    const val SLOW_POLL_MS = 300_000L
    const val MAX_FAILURES = 10
    /** At most this many flights are tracked at once (each has its own notification). */
    const val MAX_TRACKED = 2
    /** The service is worth starting this long before departure, when the app is in front. */
    const val START_LEAD_MINUTES = 30L
    /** The alarm for a flight goes off this long before departure. */
    const val ALARM_LEAD_MINUTES = 10L
    /** Tracking goes on this long past the planned landing for a landing to be seen, then ends. */
    const val CUTOFF_AFTER_LANDING_MINUTES = 30L
    /** A flight with no landing time is assumed to take this long, for the cut-off only. */
    const val UNKNOWN_DURATION_HOURS = 6L
    /** Android 15 stops a data-sync service after about six hours; the service ends itself a little before that. */
    const val MAX_RUN_MS = 5L * 60 * 60 * 1000 + 50 * 60 * 1000

    /** How long to wait before asking again, given how many answers in a row have failed. */
    fun pollDelayMs(consecutiveFailures: Int): Long = if (consecutiveFailures > MAX_FAILURES) SLOW_POLL_MS else POLL_MS

    /** The notification says "Can't refresh" once more than [MAX_FAILURES] answers in a row have failed. */
    fun refreshFailed(consecutiveFailures: Int): Boolean = consecutiveFailures > MAX_FAILURES

    private fun landsBy(s: FlightSnapshot): Instant? =
        s.bestArrival ?: s.bestDeparture?.plus(Duration.ofHours(UNKNOWN_DURATION_HOURS))

    /** The moment tracking gives up if no landing has been seen. */
    fun cutoff(s: FlightSnapshot): Instant? = landsBy(s)?.plus(Duration.ofMinutes(CUTOFF_AFTER_LANDING_MINUTES))

    /**
     * Where [flight] is right now. [live] is what the source last said (null if nothing yet, or live status is off).
     *
     * - The source saying landed, cancelled or diverted settles it.
     * - Landed is also concluded (and marked estimated) when the arrival time has passed, the source's latest answer came after
     *   that moment and no longer says the flight is in the air: it was "reconfirmed", not only guessed.
     * - With live data, "in the air" is what the source says (departed or in the air); a late flight stays in DEPARTING until it
     *   leaves. With no live data at all, the time decides: after the best departure and before the arrival it is in the air.
     * - A flight with no known departure time is WAITING until live data gives one.
     */
    fun evaluate(flight: BookedFlight, live: LiveStatus?, now: Instant, zone: ZoneId, stopped: Boolean = false): TrackDecision {
        val s = FlightTracker.snapshot(flight, live, zone)
        fun decision(phase: TrackPhase, estimated: Boolean = false) = TrackDecision(flight, s, phase, estimated)
        if (stopped) return decision(TrackPhase.STOPPED)
        when (s.kind) {
            FlightStatusKind.CANCELLED -> return decision(TrackPhase.CANCELLED)
            FlightStatusKind.DIVERTED -> return decision(TrackPhase.DIVERTED)
            FlightStatusKind.LANDED -> return decision(TrackPhase.LANDED)
            else -> Unit
        }
        val departs = s.bestDeparture ?: return decision(TrackPhase.WAITING)
        val arrives = s.bestArrival
        val updated = live?.updatedAt ?: 0L
        val liveSaysAir = live != null && (s.kind == FlightStatusKind.IN_AIR || s.kind == FlightStatusKind.DEPARTED)
        // The arrival time has passed and a later answer does not say it is still flying: it has landed.
        if (live != null && arrives != null && !now.isBefore(arrives) && updated >= arrives.toEpochMilli() &&
            s.kind == FlightStatusKind.DEPARTED
        ) return decision(TrackPhase.LANDED, estimated = true)
        val over = cutoff(s)
        if (over != null && now.isAfter(over)) return decision(TrackPhase.GAVE_UP)
        val inAir = when {
            liveSaysAir -> true
            live != null -> false
            else -> !now.isBefore(departs) && (arrives == null || now.isBefore(arrives))
        }
        if (inAir) return decision(TrackPhase.IN_AIR)
        val lead = departs.minus(Duration.ofMinutes(START_LEAD_MINUTES))
        return decision(if (!now.isBefore(lead)) TrackPhase.DEPARTING else TrackPhase.WAITING)
    }

    /** Every flight's decision, in order of departure. */
    fun decisions(
        flights: List<BookedFlight>, live: Map<String, LiveStatus>, now: Instant, zone: ZoneId, stopped: Set<String> = emptySet()
    ): List<TrackDecision> =
        flights.map { evaluate(it, live[it.key], now, zone, it.key in stopped) }
            .sortedBy { it.snapshot.bestDeparture ?: Instant.MAX }

    /** The flights to watch now: the ones that are DEPARTING or in the air, earliest first, at most [MAX_TRACKED]. */
    fun active(decisions: List<TrackDecision>): List<TrackDecision> = decisions.filter { it.phase.isActive }.take(MAX_TRACKED)

    /** Whether the service should be running at all: live status and the tracking switch on, and a flight to watch. */
    fun shouldRun(liveOn: Boolean, trackingOn: Boolean, canNotify: Boolean, decisions: List<TrackDecision>): Boolean =
        liveOn && trackingOn && canNotify && active(decisions).isNotEmpty()

    /**
     * The next moment an alarm should wake the app for a flight: [ALARM_LEAD_MINUTES] before departure, for flights that are
     * not yet within that lead and are still to come. Null when there is none. Flights that are already DEPARTING or in the air are
     * the service's business, not the alarm's.
     */
    fun nextAlarm(decisions: List<TrackDecision>, now: Instant): Instant? =
        decisions.filter { it.phase == TrackPhase.WAITING || it.phase == TrackPhase.DEPARTING }
            .mapNotNull { d -> d.snapshot.bestDeparture?.minus(Duration.ofMinutes(ALARM_LEAD_MINUTES)) }
            .filter { it.isAfter(now) }
            .minOrNull()

    /** How many of [flights] are to be notified, ever: at most two at once, the earliest two. */
    fun limit(decisions: List<TrackDecision>): List<TrackDecision> = decisions.take(MAX_TRACKED)

    /**
     * Why the service should stop right now, or null to carry on. The reasons are the ones the person can see: nothing left to
     * watch, live status or the tracking switch turned off, notifications no longer allowed, or the system's time limit.
     */
    fun stopReason(
        liveOn: Boolean, trackingOn: Boolean, canNotify: Boolean, decisions: List<TrackDecision>, runMs: Long
    ): StopReason? = when {
        !liveOn -> StopReason.LIVE_OFF
        !trackingOn -> StopReason.TRACKING_OFF
        !canNotify -> StopReason.NOTIFICATIONS_OFF
        runMs >= MAX_RUN_MS -> StopReason.TIME_LIMIT
        active(decisions).isEmpty() -> StopReason.NOTHING_TO_WATCH
        else -> null
    }
}

enum class StopReason { LIVE_OFF, TRACKING_OFF, NOTIFICATIONS_OFF, TIME_LIMIT, NOTHING_TO_WATCH }
