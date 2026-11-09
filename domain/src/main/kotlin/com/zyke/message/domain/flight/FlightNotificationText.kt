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
    val progress: Int? = null
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

    /**
     * The ongoing notice while a flight is in the air: "DEL → MAA", "In the air · lands 16:40 (12 min late)", a progress bar
     * from the time flown, and "Updated 14:32:10". [lastUpdate] is the epoch millisecond of the last answer from the live
     * source; when null the notice says the times are from the booking. [refreshFailed] is the "Can't refresh" state: the line
     * under the title then says so and when the last update was.
     */
    fun inAir(s: FlightSnapshot, now: Instant, phoneZone: ZoneId, lastUpdate: Long?, refreshFailed: Boolean = false): FlightNotice {
        val stage = if (s.kind == FlightStatusKind.DEPARTED) "Departed" else "In the air"
        val lands = arrivalClock(s)?.let { clock ->
            val versus = againstSchedule(s.scheduledArrival, s.bestArrival)?.let { " ($it)" }.orEmpty()
            " · lands $clock$versus"
        }.orEmpty()
        val line = if (refreshFailed) cantRefresh(lastUpdate, phoneZone) else stage + lands
        val sub = when {
            refreshFailed -> if (lands.isNotEmpty()) (stage + lands) else null
            lastUpdate != null -> "Updated " + updatedClock(lastUpdate, phoneZone)
            else -> "From your booking"
        }
        val percent = (FlightText.progress(s, now) * 100).toInt().coerceIn(1, 99)
        return FlightNotice(route(s), line, sub, percent)
    }

    /** Before it leaves: "DEL → MAA", "Departs 14:35 (on time) · in 25m". The service watches from half an hour before. */
    fun departing(s: FlightSnapshot, now: Instant, phoneZone: ZoneId, lastUpdate: Long?, refreshFailed: Boolean = false): FlightNotice {
        val dep = s.bestDeparture
        val head = if (dep == null) "Departure time not known" else {
            val versus = againstSchedule(s.scheduledDeparture, dep)?.let { " ($it)" }.orEmpty()
            "Departs ${FlightText.clock(dep, s.departureZone)}$versus · ${Countdown.until(now, dep)}"
        }
        val line = if (refreshFailed) cantRefresh(lastUpdate, phoneZone) else head
        val sub = when {
            refreshFailed -> head
            lastUpdate != null -> "Updated " + updatedClock(lastUpdate, phoneZone)
            else -> "From your booking"
        }
        return FlightNotice(route(s), line, sub, 0)
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
