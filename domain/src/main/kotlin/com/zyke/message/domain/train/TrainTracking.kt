package com.zyke.message.domain.train

import com.zyke.message.domain.flight.FlightTracking
import com.zyke.message.domain.flight.StopReason
import java.time.Duration
import java.time.Instant

/** Where one journey is, as far as the live notification is concerned. */
enum class TrainPhase {
    /** Not time yet (or no departure time to go by): nothing is shown. */
    WAITING,
    /** Within [TrainTracking.START_LEAD_MINUTES] of leaving the person's station: watched, with a "Reaches MS in 25 min" notice. */
    DEPARTING,
    /** Left the person's station and not there yet: the ongoing notification with the progress bar. */
    IN_TRANSIT,
    ARRIVED,
    CANCELLED,
    PARTIALLY_CANCELLED,
    DIVERTED,
    /** The planned arrival and the cut-off after it have passed without an arrival being seen. */
    GAVE_UP,
    /** The person pressed "Stop tracking" for this journey. */
    STOPPED;

    val isActive: Boolean get() = this == DEPARTING || this == IN_TRANSIT
    val isOver: Boolean get() = this == ARRIVED || this == CANCELLED || this == PARTIALLY_CANCELLED || this == DIVERTED || this == GAVE_UP
}

data class TrainDecision(val train: BookedTrain, val snapshot: TrainSnapshot, val phase: TrainPhase, val arrivalEstimated: Boolean = false) {
    val key: String get() = train.key
}

/**
 * Which journeys the train-tracking service watches, and when it stops. Pure, like [FlightTracking], whose timings it shares
 * (30 s poll, five-minute slow poll after 10 failures, start 30 min before leaving, alarm 10 min before, cut-off 30 min after the planned
 * arrival, 5 h 50 min run limit). At most [MAX_TRACKED] trips are tracked in all: flights are counted first, trains take what is left
 * ([slotsLeft]).
 */
object TrainTracking {
    const val POLL_MS = FlightTracking.POLL_MS
    const val MAX_TRACKED = FlightTracking.MAX_TRACKED
    const val START_LEAD_MINUTES = FlightTracking.START_LEAD_MINUTES
    const val ALARM_LEAD_MINUTES = FlightTracking.ALARM_LEAD_MINUTES
    const val CUTOFF_AFTER_ARRIVAL_MINUTES = FlightTracking.CUTOFF_AFTER_LANDING_MINUTES
    /** A train with no arrival time is assumed to run this long, for the cut-off only. */
    const val UNKNOWN_DURATION_HOURS = 24L

    fun pollDelayMs(consecutiveFailures: Int): Long = FlightTracking.pollDelayMs(consecutiveFailures)
    fun refreshFailed(consecutiveFailures: Int): Boolean = FlightTracking.refreshFailed(consecutiveFailures)

    /** How many more trips may be tracked when [otherActive] (flights) already are. */
    fun slotsLeft(otherActive: Int): Int = (MAX_TRACKED - otherActive).coerceAtLeast(0)

    fun cutoff(s: TrainSnapshot): Instant? =
        (s.bestArrival ?: s.bestDeparture?.plus(Duration.ofHours(UNKNOWN_DURATION_HOURS)))?.plus(Duration.ofMinutes(CUTOFF_AFTER_ARRIVAL_MINUTES))

    fun evaluate(train: BookedTrain, live: TrainLive?, now: Instant, stopped: Boolean = false): TrainDecision {
        val s = TrainTracker.snapshot(train, live, now)
        fun decision(phase: TrainPhase, estimated: Boolean = false) = TrainDecision(train, s, phase, estimated)
        if (stopped) return decision(TrainPhase.STOPPED)
        when (s.kind) {
            TrainStatusKind.CANCELLED -> return decision(TrainPhase.CANCELLED)
            TrainStatusKind.PARTIALLY_CANCELLED -> return decision(TrainPhase.PARTIALLY_CANCELLED)
            TrainStatusKind.DIVERTED -> return decision(TrainPhase.DIVERTED)
            TrainStatusKind.ARRIVED -> return decision(TrainPhase.ARRIVED)
            else -> Unit
        }
        val departs = s.bestDeparture ?: return decision(TrainPhase.WAITING)
        val arrives = s.bestArrival
        val updated = live?.updatedAt ?: 0L
        // The arrival time has passed and a later answer does not say it is still running: it has arrived.
        if (live != null && arrives != null && !now.isBefore(arrives) && updated >= arrives.toEpochMilli() && s.departed) {
            return decision(TrainPhase.ARRIVED, estimated = true)
        }
        val over = cutoff(s)
        if (over != null && now.isAfter(over)) return decision(TrainPhase.GAVE_UP)
        val inTransit = if (live != null) s.departed else !now.isBefore(departs) && (arrives == null || now.isBefore(arrives))
        if (inTransit) return decision(TrainPhase.IN_TRANSIT)
        val lead = departs.minus(Duration.ofMinutes(START_LEAD_MINUTES))
        return decision(if (!now.isBefore(lead)) TrainPhase.DEPARTING else TrainPhase.WAITING)
    }

    fun decisions(trains: List<BookedTrain>, live: Map<String, TrainLive>, now: Instant, stopped: Set<String> = emptySet()): List<TrainDecision> =
        trains.map { evaluate(it, live[it.key], now, it.key in stopped) }.sortedBy { it.snapshot.bestDeparture ?: Instant.MAX }

    /** The journeys to watch now: DEPARTING or in transit, earliest first, at most [slots]. */
    fun active(decisions: List<TrainDecision>, slots: Int = MAX_TRACKED): List<TrainDecision> =
        decisions.filter { it.phase.isActive }.take(slots.coerceAtLeast(0))

    fun shouldRun(liveOn: Boolean, trackingOn: Boolean, canNotify: Boolean, decisions: List<TrainDecision>, slots: Int = MAX_TRACKED): Boolean =
        liveOn && trackingOn && canNotify && active(decisions, slots).isNotEmpty()

    /** The next alarm: [ALARM_LEAD_MINUTES] before departure, for journeys not yet within that lead. Null when there is none. */
    fun nextAlarm(decisions: List<TrainDecision>, now: Instant): Instant? =
        decisions.filter { it.phase == TrainPhase.WAITING || it.phase == TrainPhase.DEPARTING }
            .mapNotNull { d -> d.snapshot.bestDeparture?.minus(Duration.ofMinutes(ALARM_LEAD_MINUTES)) }
            .filter { it.isAfter(now) }
            .minOrNull()

    fun stopReason(
        liveOn: Boolean, trackingOn: Boolean, canNotify: Boolean, decisions: List<TrainDecision>, runMs: Long, slots: Int = MAX_TRACKED
    ): StopReason? = when {
        !liveOn -> StopReason.LIVE_OFF
        !trackingOn -> StopReason.TRACKING_OFF
        !canNotify -> StopReason.NOTIFICATIONS_OFF
        runMs >= FlightTracking.MAX_RUN_MS -> StopReason.TIME_LIMIT
        active(decisions, slots).isEmpty() -> StopReason.NOTHING_TO_WATCH
        else -> null
    }
}
