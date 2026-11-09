package com.zyke.message.domain.flight

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What a flight notification says: a title, one line under it, and (for the one in the air) how far along it is, 0..100. */
data class FlightNotice(
    val title: String,
    val text: String,
    /** The smaller line, such as "Updated 14:32:10"; null when there is none. */
    val subText: String? = null,
    /** Percent of the flight flown, for the progress bar; null for a notice with no bar. */
    val progress: Int? = null,
    /** The very short text for a status-bar chip (Android 16 Live Updates), at most [FlightNotificationText.CHIP_MAX] characters; null when none. */
    val chip: String? = null,
    /** The moment the system counts down to (landing, or departure before it leaves); null when no countdown makes sense. */
    val countdownTo: Instant? = null,
    /** Positions (0..100) of the marks on the bar: take-off and landing. Empty for a notice with no bar. */
    val points: List<Int> = emptyList()
)

/**
 * The words of the flight-tracking notifications, in one place so they are tested. Times are on each airport's own clock
 * (24-hour), except "Updated", which is on the phone's clock. Nothing here is ever logged: the texts carry the route, which
 * is the person's own travel.
 */
object FlightNotificationText {
    /** Arriving within this many minutes of the schedule, either way, is "on time". */
    const val ON_TIME_MINUTES = 5

    /** What the lock screen shows in place of the route, the times and the status. */
    const val PUBLIC_TITLE = "Flight in progress"
    const val PUBLIC_TEXT = "Unlock to see your flight"

    /** "DEL → MAA" (airport codes), or the cities, or what is known; "Your flight" when nothing is. */
    fun route(s: FlightSnapshot): String {
        val from = s.origin?.label
        val to = s.destination?.label
        return when {
            from != null && to != null -> "$from → $to"
            else -> from ?: to ?: "Your flight ${s.flight.code}"
        }
    }

    /** "on time", "6 min early", "12 min late", "1 h 5 min late"; null when the schedule or the time is not known. */
    fun againstSchedule(scheduled: Instant?, actual: Instant?): String? {
        if (scheduled == null || actual == null) return null
        val minutes = Duration.between(scheduled, actual).toMinutes()
        return when {
            kotlin.math.abs(minutes) < ON_TIME_MINUTES -> "on time"
            minutes < 0 -> FlightText.delayWords((-minutes).toInt()) + " early"
            else -> FlightText.delayWords(minutes.toInt()) + " late"
        }
    }

    /** The arrival clock on the destination's clock, "16:40", with " +1" after midnight. Null when no time is known. */
    fun arrivalClock(s: FlightSnapshot): String? = FlightText.arrivalClock(s)

    /** "14:32:10" on the phone's clock. */
    fun updatedClock(updatedAtMillis: Long, zone: ZoneId): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(updatedAtMillis).atZone(zone))

    private fun hoursMinutes(millis: Long, zone: ZoneId): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))

    /** The longest chip text the status bar shows. */
    const val CHIP_MAX = 7

    /** Take-off and landing on the bar, as positions out of 100. */
    val PROGRESS_POINTS: List<Int> = listOf(0, 100)

    /**
     * The short text for the status-bar chip and the lock screen, at most [CHIP_MAX] characters: "1h 05m", "45m", "5m", "now",
     * "1d 2h". Null when [target] is not known. Minutes are rounded down, so it never promises more time than there is.
     */
    fun chip(now: Instant, target: Instant?): String? {
        if (target == null) return null
        val minutes = Duration.between(now, target).toMinutes()
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
            else -> {
                val days = minutes / (24 * 60)
                if (days > 9) "9d+" else "${days}d ${minutes % (24 * 60) / 60}h"
            }
        }
    }

    /** The target for the system's own countdown: only when it is still ahead, so the clock never counts up past it. */
    fun countdownTarget(now: Instant, target: Instant?): Instant? = target?.takeIf { it.isAfter(now) }

    /** "Lands 16:40 · on time", "Lands 16:52 · 12 min late"; "Lands 16:40" when no schedule is known; null with no time at all. */
    fun landsLine(s: FlightSnapshot): String? {
        val clock = arrivalClock(s) ?: return null
        val versus = againstSchedule(s.scheduledArrival, s.bestArrival)?.let { " · $it" }.orEmpty()
        return "Lands $clock$versus"
    }

    /**
     * The ongoing notice while a flight is in the air, glanceable: "In the air · DEL → MAA", "Lands 16:40 · on time", a bar
     * from the time flown, the chip "1h 05m" (time to landing) and the landing as the moment to count down to, and
     * "Updated 14:32:10". [lastUpdate] is the epoch millisecond of the last answer from the live source; when null the notice says
     * the times are from the booking. [refreshFailed] is the "Can't refresh" state: the line under the title then says so and when
     * the last update was.
     */
    fun inAir(s: FlightSnapshot, now: Instant, phoneZone: ZoneId, lastUpdate: Long?, refreshFailed: Boolean = false): FlightNotice {
        val stage = if (s.kind == FlightStatusKind.DEPARTED) "Departed" else "In the air"
        val lands = landsLine(s)
        val line = if (refreshFailed) cantRefresh(lastUpdate, phoneZone) else lands ?: "Landing time not known"
        val sub = when {
            refreshFailed -> lands
            lastUpdate != null -> "Updated " + updatedClock(lastUpdate, phoneZone)
            else -> "From your booking"
        }
        val percent = (FlightText.progress(s, now) * 100).toInt().coerceIn(1, 99)
        return FlightNotice(
            "$stage · ${route(s)}", line, sub, percent,
            chip = chip(now, s.bestArrival), countdownTo = countdownTarget(now, s.bestArrival), points = PROGRESS_POINTS
        )
    }

    /**
     * Before it leaves, the headline is what matters most: "Departs in 25 min", "Boarding", "Delayed 12 min" (or "Departing now");
     * the line under it is the route and the time, "DEL → MAA · 14:35". The service watches from half an hour before.
     */
    fun departing(s: FlightSnapshot, now: Instant, phoneZone: ZoneId, lastUpdate: Long?, refreshFailed: Boolean = false): FlightNotice {
        val dep = s.bestDeparture
        val late = againstSchedule(s.scheduledDeparture, dep)?.takeIf { it.endsWith(" late") }?.removeSuffix(" late")
        val minutesLeft = dep?.let { Duration.between(now, it).toMinutes() }
        val title = when {
            s.kind == FlightStatusKind.BOARDING -> "Boarding"
            late != null -> "Delayed $late"
            s.kind == FlightStatusKind.DELAYED && s.delayMinutes >= ON_TIME_MINUTES -> "Delayed " + FlightText.delayWords(s.delayMinutes)
            dep == null -> "Departure time not known"
            minutesLeft != null && minutesLeft >= 1 -> "Departs in " + FlightText.delayWords(minutesLeft.toInt())
            else -> "Departing now"
        }
        val head = listOfNotNull(route(s), dep?.let { FlightText.clock(it, s.departureZone) }).joinToString(" · ")
        val line = if (refreshFailed) cantRefresh(lastUpdate, phoneZone) else head
        val sub = when {
            refreshFailed -> head
            lastUpdate != null -> "Updated " + updatedClock(lastUpdate, phoneZone)
            else -> "From your booking"
        }
        return FlightNotice(
            title, line, sub, 0,
            chip = chip(now, dep), countdownTo = countdownTarget(now, dep), points = PROGRESS_POINTS
        )
    }

    /** "Can't refresh · last update 14:32"; without an update yet, "Can't refresh". */
    fun cantRefresh(lastUpdate: Long?, phoneZone: ZoneId): String =
        if (lastUpdate == null) "Can't refresh" else "Can't refresh · last update " + hoursMinutes(lastUpdate, phoneZone)

    /**
     * The dismissible notice after landing: "Landed · DEL → MAA at 16:34", then "6 min early · Terminal 1 · Belt 4". The
     * terminal and belt are only said when the live source gave them. [estimated] is a landing worked out from the arrival time
     * and a later answer rather than reported (it says "around").
     */
    fun landed(s: FlightSnapshot, estimated: Boolean = false): FlightNotice {
        val at = arrivalClock(s)?.let { (if (estimated) " around " else " at ") + it }.orEmpty()
        val parts = listOfNotNull(
            againstSchedule(s.scheduledArrival, s.bestArrival)?.replaceFirstChar { it.uppercase() },
            s.arrivalTerminal?.let { "Terminal $it" },
            s.baggage?.let { "Belt $it" }
        )
        return FlightNotice("Landed · ${route(s)}$at", parts.joinToString(" · ").ifEmpty { "Landed" })
    }

    /** The landing on one line, as the lock-screen-free expanded text: "Landed 6 min early at 16:34 · Terminal 1 · Belt 4". */
    fun landedOneLine(s: FlightSnapshot, estimated: Boolean = false): String {
        val versus = againstSchedule(s.scheduledArrival, s.bestArrival)?.let { " $it" }.orEmpty()
        val at = arrivalClock(s)?.let { (if (estimated) " around " else " at ") + it }.orEmpty()
        val tail = listOfNotNull(s.arrivalTerminal?.let { "Terminal $it" }, s.baggage?.let { "Belt $it" })
        return ("Landed$versus$at" + tail.joinToString("") { " · $it" })
    }

    fun cancelled(s: FlightSnapshot): FlightNotice = FlightNotice("Cancelled · ${route(s)}", "Check with the airline for another flight")

    fun diverted(s: FlightSnapshot): FlightNotice = FlightNotice("Diverted · ${route(s)}", "Check with the airline for where it lands")

    /** When tracking ends because the landing was never confirmed (the cut-off after the planned landing). */
    fun unconfirmed(s: FlightSnapshot): FlightNotice {
        val due = arrivalClock(s)?.let { "was due $it" } ?: "has finished"
        return FlightNotice("Tracking ended · ${route(s)}", "Landing not confirmed; $due")
    }

    /** The dismissible notice when the system limits the service (Android 15 caps a data-sync service at about six hours). */
    fun paused(): FlightNotice = FlightNotice("Tracking paused", "Open Zyke to resume")

    /** The quiet reminder when live status is off or the service cannot start from the background: "Tap to start live tracking". */
    fun tapToStart(s: FlightSnapshot, now: Instant): FlightNotice {
        val dep = s.bestDeparture
        val when_ = dep?.let { "Departs ${FlightText.clock(it, s.departureZone)} · ${Countdown.until(now, it)}" } ?: "Your flight is about to leave"
        return FlightNotice(route(s), "$when_ · tap to start live tracking")
    }

    /** The ordinary reminder when live status is off: times from the booking only, nothing ongoing. */
    fun reminder(s: FlightSnapshot, now: Instant): FlightNotice {
        val dep = s.bestDeparture
        val head = dep?.let { "Departs ${FlightText.clock(it, s.departureZone)} · ${Countdown.until(now, it)}" } ?: "Your flight is today"
        val tail = arrivalClock(s)?.let { " · lands $it" }.orEmpty()
        return FlightNotice(route(s), head + tail + " (from your booking)")
    }
}
