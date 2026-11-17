package com.zyke.message.notifications

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.flight.FlightNotice
import com.zyke.message.domain.flight.StopReason
import com.zyke.message.domain.train.BookedTrain
import com.zyke.message.domain.train.ManualTrains
import com.zyke.message.domain.train.TrainDecision
import com.zyke.message.domain.train.TrainNotificationText
import com.zyke.message.domain.train.TrainPhase
import com.zyke.message.domain.train.TrainTracking
import java.time.Instant
import java.time.ZoneId

/**
 * The train half of the live trip notification's start rules and alarm. The same shape as [FlightTrackingControl], whose honest
 * description (in [TripTrackingControl]) applies (an activity in front may start the service; the inexact alarm,
 * 10 minutes before the train leaves the person's station, can only start it before Android 12 and otherwise leaves a "Tap to start live
 * tracking" notification; nothing starts it from the boot broadcast), with one difference: at most two trips are tracked in all, so a
 * train gets only the places flights have left over ([slots]).
 */
object TrainTrackingControl : TripTrackingControl<BookedTrain, TrainDecision>() {
    override val logTag: String get() = "train"
    override val requestCode: Int get() = 812
    override val receiverClass: Class<out BroadcastReceiver> get() = TrainAlarmReceiver::class.java
    override val serviceClass: Class<out Service> get() = TrainTrackingService::class.java
    override val notifier: TripNotifier get() = TrainNotifier

    @Volatile override var running: Boolean = false

    /** The journeys found in texts (as last written by Home) and those added by hand, as one list. */
    fun trains(container: AppContainer): List<BookedTrain> = ManualTrains.merge(container.trainPlan.fromTexts(), container.manualTrains.trains.value)

    /** How many more trips may be tracked: two in all, flights counted first. */
    fun slots(container: AppContainer): Int =
        TrainTracking.slotsLeft(com.zyke.message.domain.flight.FlightTracking.active(FlightTrackingControl.decisions(container)).size)

    fun active(container: AppContainer): List<TrainDecision> = active(container, Instant.now())

    override fun key(d: TrainDecision): String = d.key
    override fun isActive(d: TrainDecision): Boolean = d.phase.isActive
    override fun liveEnabled(container: AppContainer): Boolean = container.trains.liveEnabled.value
    override fun trackingEnabled(container: AppContainer): Boolean = container.trains.trackingEnabled.value

    // The time zone is not asked for: a train's times are read in the stations' own zone.
    override fun decisions(container: AppContainer, now: Instant, zone: ZoneId): List<TrainDecision> =
        TrainTracking.decisions(trains(container), container.trains.live.value.statuses, now, container.trainPlan.stopped())

    override fun active(container: AppContainer, now: Instant): List<TrainDecision> =
        TrainTracking.active(decisions(container, now, ZoneId.systemDefault()), slots(container))

    override fun activeOf(container: AppContainer, ds: List<TrainDecision>): List<TrainDecision> =
        TrainTracking.active(ds, slots(container))

    override fun shouldRun(container: AppContainer, live: Boolean, tracking: Boolean, canNotify: Boolean, ds: List<TrainDecision>): Boolean =
        TrainTracking.shouldRun(live, tracking, canNotify, ds, slots(container))

    override fun stopReason(
        container: AppContainer, liveOn: Boolean, trackingOn: Boolean, canNotify: Boolean, ds: List<TrainDecision>, runMs: Long
    ): StopReason? = TrainTracking.stopReason(liveOn, trackingOn, canNotify, ds, runMs, slots(container))

    override fun nextAlarm(ds: List<TrainDecision>, now: Instant): Instant? = TrainTracking.nextAlarm(ds, now)
    override fun pollDelayMs(consecutiveFailures: Int): Long = TrainTracking.pollDelayMs(consecutiveFailures)
    override fun refreshFailed(consecutiveFailures: Int): Boolean = TrainTracking.refreshFailed(consecutiveFailures)

    override fun savePlan(container: AppContainer, fromTexts: List<BookedTrain>) {
        container.trainPlan.saveFromTexts(fromTexts)
    }

    override fun pruneManual(container: AppContainer) {
        container.manualTrains.prune(Instant.now())
    }

    override fun markReminded(container: AppContainer, key: String): Boolean = container.trainPlan.markReminded(key)

    override fun stopTracking(container: AppContainer, key: String) {
        container.trainPlan.stop(key)
    }

    override fun tapToStart(d: TrainDecision, now: Instant): FlightNotice = TrainNotificationText.tapToStart(d.snapshot, now)
    override fun reminder(d: TrainDecision, now: Instant): FlightNotice = TrainNotificationText.reminder(d.snapshot, now)

    override fun lastAttemptAt(container: AppContainer, key: String): Long = container.trains.lastAttemptAt(key)
    override fun failedKeys(container: AppContainer): Set<String> = container.trains.live.value.failed
    override fun updatedAt(container: AppContainer, key: String): Long? =
        container.trains.live.value.statuses[key]?.updatedAt?.takeIf { it > 0 }

    override suspend fun refresh(container: AppContainer, toAsk: List<TrainDecision>, zone: ZoneId) {
        container.trains.refresh(toAsk.map { it.train })
    }

    override fun ongoingNotice(d: TrainDecision, now: Instant, zone: ZoneId, updatedAt: Long?, refreshFailed: Boolean): FlightNotice =
        if (d.phase == TrainPhase.DEPARTING) TrainNotificationText.approaching(d.snapshot, now, zone, updatedAt, refreshFailed)
        else TrainNotificationText.inTransit(d.snapshot, now, zone, updatedAt, refreshFailed)

    override fun finalNotice(d: TrainDecision): FlightNotice? = when (d.phase) {
        TrainPhase.ARRIVED -> TrainNotificationText.arrived(d.snapshot, d.arrivalEstimated)
        TrainPhase.CANCELLED -> TrainNotificationText.cancelled(d.snapshot)
        TrainPhase.PARTIALLY_CANCELLED -> TrainNotificationText.partiallyCancelled(d.snapshot)
        TrainPhase.DIVERTED -> TrainNotificationText.diverted(d.snapshot)
        TrainPhase.GAVE_UP -> TrainNotificationText.unconfirmed(d.snapshot)
        else -> null
    }
}

/** Hears the train alarm. Not exported: only the system's alarm manager, on this app's behalf, reaches it. */
class TrainAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        TrainTrackingControl.onAlarm(context)
    }
}
