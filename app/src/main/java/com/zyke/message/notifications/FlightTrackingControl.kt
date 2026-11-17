package com.zyke.message.notifications

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.flight.BookedFlight
import com.zyke.message.domain.flight.FlightNotice
import com.zyke.message.domain.flight.FlightNotificationText
import com.zyke.message.domain.flight.FlightTracking
import com.zyke.message.domain.flight.ManualFlights
import com.zyke.message.domain.flight.StopReason
import com.zyke.message.domain.flight.TrackDecision
import com.zyke.message.domain.flight.TrackPhase
import java.time.Instant
import java.time.ZoneId

/**
 * The flight half of the live trip notification's start rules and alarm. The rules themselves (how it starts, the one inexact
 * alarm, the tap-to-start fallback) are in [TripTrackingControl]; this supplies what only flights know: where the flights are,
 * what [FlightTracking] says about them and the words of [FlightNotificationText].
 */
object FlightTrackingControl : TripTrackingControl<BookedFlight, TrackDecision>() {
    override val logTag: String get() = "flight"
    override val requestCode: Int get() = 811
    override val receiverClass: Class<out BroadcastReceiver> get() = FlightAlarmReceiver::class.java
    override val serviceClass: Class<out Service> get() = FlightTrackingService::class.java
    override val notifier: TripNotifier get() = FlightNotifier

    @Volatile override var running: Boolean = false

    /** The flights found in texts (as last written by Home) and those added by hand, as one list. */
    fun flights(container: AppContainer): List<BookedFlight> =
        ManualFlights.merge(container.flightPlan.fromTexts(), container.manualFlights.flights.value)

    override fun key(d: TrackDecision): String = d.key
    override fun isActive(d: TrackDecision): Boolean = d.phase.isActive
    override fun liveEnabled(container: AppContainer): Boolean = container.flights.liveEnabled.value
    override fun trackingEnabled(container: AppContainer): Boolean = container.flights.trackingEnabled.value

    override fun decisions(container: AppContainer, now: Instant, zone: ZoneId): List<TrackDecision> =
        FlightTracking.decisions(flights(container), container.flights.live.value.statuses, now, zone, container.flightPlan.stopped())

    override fun active(container: AppContainer, now: Instant): List<TrackDecision> =
        FlightTracking.active(decisions(container, now, ZoneId.systemDefault()))

    override fun activeOf(container: AppContainer, ds: List<TrackDecision>): List<TrackDecision> = FlightTracking.active(ds)

    override fun shouldRun(container: AppContainer, live: Boolean, tracking: Boolean, canNotify: Boolean, ds: List<TrackDecision>): Boolean =
        FlightTracking.shouldRun(live, tracking, canNotify, ds)

    override fun stopReason(
        container: AppContainer, liveOn: Boolean, trackingOn: Boolean, canNotify: Boolean, ds: List<TrackDecision>, runMs: Long
    ): StopReason? = FlightTracking.stopReason(liveOn, trackingOn, canNotify, ds, runMs)

    override fun nextAlarm(ds: List<TrackDecision>, now: Instant): Instant? = FlightTracking.nextAlarm(ds, now)
    override fun pollDelayMs(consecutiveFailures: Int): Long = FlightTracking.pollDelayMs(consecutiveFailures)
    override fun refreshFailed(consecutiveFailures: Int): Boolean = FlightTracking.refreshFailed(consecutiveFailures)

    override fun savePlan(container: AppContainer, fromTexts: List<BookedFlight>) {
        container.flightPlan.saveFromTexts(fromTexts)
    }
    override fun pruneManual(container: AppContainer) {
        container.manualFlights.prune(Instant.now(), ZoneId.systemDefault())
    }
    override fun markReminded(container: AppContainer, key: String): Boolean = container.flightPlan.markReminded(key)
    override fun stopTracking(container: AppContainer, key: String) {
        container.flightPlan.stop(key)
    }

    override fun tapToStart(d: TrackDecision, now: Instant): FlightNotice = FlightNotificationText.tapToStart(d.snapshot, now)
    override fun reminder(d: TrackDecision, now: Instant): FlightNotice = FlightNotificationText.reminder(d.snapshot, now)

    override fun lastAttemptAt(container: AppContainer, key: String): Long = container.flights.lastAttemptAt(key)
    override fun failedKeys(container: AppContainer): Set<String> = container.flights.live.value.failed
    override fun updatedAt(container: AppContainer, key: String): Long? =
        container.flights.live.value.statuses[key]?.updatedAt?.takeIf { it > 0 }

    override suspend fun refresh(container: AppContainer, toAsk: List<TrackDecision>, zone: ZoneId) {
        container.flights.refresh(toAsk.map { it.flight }, zone)
    }

    override fun ongoingNotice(d: TrackDecision, now: Instant, zone: ZoneId, updatedAt: Long?, refreshFailed: Boolean): FlightNotice =
        if (d.phase == TrackPhase.DEPARTING) FlightNotificationText.departing(d.snapshot, now, zone, updatedAt, refreshFailed)
        else FlightNotificationText.inAir(d.snapshot, now, zone, updatedAt, refreshFailed)

    override fun finalNotice(d: TrackDecision): FlightNotice? = when (d.phase) {
        TrackPhase.LANDED -> FlightNotificationText.landed(d.snapshot, d.landingEstimated)
        TrackPhase.CANCELLED -> FlightNotificationText.cancelled(d.snapshot)
        TrackPhase.DIVERTED -> FlightNotificationText.diverted(d.snapshot)
        TrackPhase.GAVE_UP -> FlightNotificationText.unconfirmed(d.snapshot)
        else -> null
    }
}

/** Hears the flight alarm. Not exported: only the system's alarm manager, on this app's behalf, reaches it. */
class FlightAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        FlightTrackingControl.onAlarm(context)
    }
}
