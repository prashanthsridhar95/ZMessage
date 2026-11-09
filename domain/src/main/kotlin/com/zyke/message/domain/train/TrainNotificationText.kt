package com.zyke.message.domain.train

import com.zyke.message.domain.flight.FlightNotice
import com.zyke.message.domain.flight.FlightNotificationText
import com.zyke.message.domain.flight.FlightText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The words of the train-tracking notifications, in one place so they are tested. They reuse [FlightNotice] (a title, a line, a small
 * line, a bar, a chip and a countdown). Times are on the Indian clock (24-hour) except "Updated", which is on the phone's. Nothing here
 * is ever logged: the texts carry the route, which is the person's own travel.
 */
object TrainNotificationText {
    const val PUBLIC_TITLE = "Train journey in progress"
    const val PUBLIC_TEXT = "Unlock to see your train"

    /** "MS → TPJ" (station codes), or the names, or what is known; "Your train 12633" when nothing is. */
    fun route(s: TrainSnapshot): String {
        val from = s.from?.label
        val to = s.to?.label
        return when {
            from != null && to != null -> "$from → $to"
            else -> from ?: to ?: "Your train ${s.train.number}"
        }
    }

    /** The place the person gets off, as people say it: "Tiruchirappalli", else the code, else "your station". */
    private fun destinationName(s: TrainSnapshot): String = s.to?.city?.takeIf { it.isNotBlank() } ?: s.to?.label ?: "your destination"

    private fun late(delay: Int): String? = when {
        delay >= TrainText.ON_TIME_MINUTES -> "Running ${FlightText.delayWords(delay)} late"
        delay <= -TrainText.ON_TIME_MINUTES -> "Running ${FlightText.delayWords(-delay)} early"
        else -> null
    }

    private fun sub(s: TrainSnapshot, phoneZone: ZoneId, lastUpdate: Long?): String =
        s.train.number + " · " + (lastUpdate?.let { "Updated " + FlightNotificationText.updatedClock(it, phoneZone) } ?: "From your booking")

    /**
     * The ongoing notice while the train is under way: "Arrives Tiruchirappalli 04:47 (12 min late)", the line
     * "Running 12 min late · next: Villupuram 03:10", a bar from the time travelled, the chip "3h 12m" and the arrival as the moment to
     * count down to. [refreshFailed] is the "Can't refresh" state.
     */
    fun inTransit(s: TrainSnapshot, now: Instant, phoneZone: ZoneId, lastUpdate: Long?, refreshFailed: Boolean = false): FlightNotice {
        val clock = TrainText.arrivalClock(s)
        val versus = TrainText.againstSchedule(s.scheduledArrival, s.bestArrival)
        val title = if (clock != null) "Arrives ${destinationName(s)} $clock" + (versus?.let { " ($it)" } ?: "") else "On the way · ${route(s)}"
        val position = s.position
        val here = position?.takeIf { it.atStation }?.let { "at ${it.name}" }
        val next = s.nextStop?.let { n -> "next: ${TrainText.stopName(n)}" + (TrainText.expectedClock(n, s.delayMinutes)?.let { " $it" } ?: "") }
        val status = late(s.delayMinutes) ?: "On time"
        val line = if (refreshFailed) FlightNotificationText.cantRefresh(lastUpdate, phoneZone)
        else listOfNotNull(status, here ?: next).joinToString(" · ")
        val percent = (TrainText.progress(s, now) * 100).toInt().coerceIn(1, 99)
        return FlightNotice(
            title, line, if (refreshFailed) listOfNotNull(status, here ?: next).joinToString(" · ") else sub(s, phoneZone, lastUpdate), percent,
            chip = FlightNotificationText.chip(now, s.bestArrival), countdownTo = FlightNotificationText.countdownTarget(now, s.bestArrival),
            points = FlightNotificationText.PROGRESS_POINTS
        )
    }

    /**
     * Before the train has left the person's station: "Reaches MS in 25 min" (or "Departs in 25 min"), with "(25 min late)" when it
     * is late, and the route and time under it.
     */
    fun approaching(s: TrainSnapshot, now: Instant, phoneZone: ZoneId, lastUpdate: Long?, refreshFailed: Boolean = false): FlightNotice {
        val dep = s.bestDeparture
        val reaches = s.reachesBoarding
        val delayed = s.delayMinutes.takeIf { it >= TrainText.ON_TIME_MINUTES }?.let { " (${FlightText.delayWords(it)} late)" }.orEmpty()
        val target = reaches?.takeIf { it.isAfter(now) } ?: dep
        val minutesLeft = target?.let { Duration.between(now, it).toMinutes() }
        val here = s.from?.label ?: "your station"
        val title = when {
            s.kind == TrainStatusKind.AT_STATION -> "Waiting at $here"
            dep == null -> "Departure time not known"
            reaches != null && minutesLeft != null && minutesLeft >= 1 && reaches.isAfter(now) -> "Reaches $here in " + FlightText.delayWords(minutesLeft.toInt()) + delayed
            minutesLeft != null && minutesLeft >= 1 -> "Departs in " + FlightText.delayWords(minutesLeft.toInt()) + delayed
            else -> "Departing now"
        }
        val head = listOfNotNull(route(s), dep?.let { TrainText.clock(it) }).joinToString(" · ")
        val line = if (refreshFailed) FlightNotificationText.cantRefresh(lastUpdate, phoneZone) else head
        return FlightNotice(
            title, line, if (refreshFailed) head else sub(s, phoneZone, lastUpdate), 0,
            chip = FlightNotificationText.chip(now, target), countdownTo = FlightNotificationText.countdownTarget(now, target),
            points = FlightNotificationText.PROGRESS_POINTS
        )
    }

    /**
     * The dismissible notice at the end: "Arrived at TPJ at 04:41", then "6 min late (scheduled 04:35) · Platform 2". [estimated] is an
     * arrival worked out from the schedule and a later answer rather than reported (it says "around").
     */
    fun arrived(s: TrainSnapshot, estimated: Boolean = false): FlightNotice {
        val at = s.bestArrival?.let { (if (estimated) " around " else " at ") + TrainText.clock(it) }.orEmpty()
        val versus = TrainText.againstSchedule(s.scheduledArrival, s.bestArrival)
        val scheduled = s.scheduledArrival?.takeIf { versus != null && versus != "on time" }?.let { " (scheduled ${TrainText.clock(it)})" }.orEmpty()
        val parts = listOfNotNull(versus?.let { it.replaceFirstChar { c -> c.uppercase() } + scheduled }, s.platform?.let { "Platform $it" })
        return FlightNotice("Arrived at ${s.to?.label ?: "your destination"}$at", parts.joinToString(" · ").ifEmpty { "Arrived" })
    }

    fun cancelled(s: TrainSnapshot): FlightNotice = FlightNotice("Cancelled · ${route(s)}", "Check with Indian Railways for what to do")

    fun partiallyCancelled(s: TrainSnapshot): FlightNotice =
        FlightNotice("Partly cancelled · ${route(s)}", "Your stations may be affected. Check with Indian Railways")

    fun diverted(s: TrainSnapshot): FlightNotice = FlightNotice("Diverted · ${route(s)}", "Check with Indian Railways for the new route")

    /** When tracking ends because the arrival was never confirmed (the cut-off after the planned arrival). */
    fun unconfirmed(s: TrainSnapshot): FlightNotice {
        val due = s.scheduledArrival?.let { "was due ${TrainText.clock(it)}" } ?: "has finished"
        return FlightNotice("Tracking ended · ${route(s)}", "Arrival not confirmed; $due")
    }

    fun paused(): FlightNotice = FlightNotificationText.paused()

    /** The quiet prompt: "Tap to start live tracking". */
    fun tapToStart(s: TrainSnapshot, now: Instant): FlightNotice {
        val dep = s.bestDeparture
        val when_ = dep?.let { "Departs ${TrainText.clock(it)} · ${com.zyke.message.domain.flight.Countdown.until(now, it)}" } ?: "Your train is about to leave"
        return FlightNotice(route(s), "$when_ · tap to start live tracking")
    }

    /** The ordinary reminder when live train status is off: times from the booking only, nothing ongoing. */
    fun reminder(s: TrainSnapshot, now: Instant): FlightNotice {
        val dep = s.bestDeparture
        val head = dep?.let { "Departs ${TrainText.clock(it)} · ${com.zyke.message.domain.flight.Countdown.until(now, it)}" } ?: "Your train is today"
        val tail = TrainText.arrivalClock(s)?.let { " · arrives $it" }.orEmpty()
        return FlightNotice(route(s), head + tail + " (from your booking)")
    }
}
