package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import com.zyke.message.domain.flight.StopReason
import com.zyke.message.domain.train.TrainFixtures.DOJ
import com.zyke.message.domain.train.TrainFixtures.ist
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainTrackingTest {
    private val train = BookedTrain("12633", "Kanniyakumari Express", DOJ, Place("MS", "Chennai Egmore"), Place("TPJ", "Tiruchirappalli"), LocalTime.of(21, 30), LocalTime.of(4, 35))
    private fun live(json: String, at: Instant) = (IndianRailApiParser.parse(json, train, DOJ, at.toEpochMilli()) as TrainLookup.Found).live
    private fun phase(now: Instant, l: TrainLive? = null, t: BookedTrain = train, stopped: Boolean = false) = TrainTracking.evaluate(t, l, now, stopped).phase

    @Test fun theBooleansOfEachPhase() {
        assertTrue(TrainPhase.DEPARTING.isActive && TrainPhase.IN_TRANSIT.isActive)
        assertFalse(TrainPhase.WAITING.isActive || TrainPhase.ARRIVED.isActive || TrainPhase.STOPPED.isActive)
        assertTrue(TrainPhase.ARRIVED.isOver && TrainPhase.CANCELLED.isOver && TrainPhase.PARTIALLY_CANCELLED.isOver && TrainPhase.DIVERTED.isOver && TrainPhase.GAVE_UP.isOver)
        assertFalse(TrainPhase.STOPPED.isOver)
    }

    @Test fun withoutLiveDataTheClockDecides() {
        assertEquals(TrainPhase.WAITING, phase(ist(12, 20, 59)))
        assertEquals(TrainPhase.DEPARTING, phase(ist(12, 21, 0)))
        assertEquals(TrainPhase.IN_TRANSIT, phase(ist(12, 21, 30)))
        assertEquals(TrainPhase.IN_TRANSIT, phase(ist(13, 3, 0)))
        // The planned arrival has passed with no news: it carries on for the cut-off, then gives up.
        assertEquals(TrainPhase.GAVE_UP, phase(ist(13, 5, 6)))
        assertEquals(TrainPhase.STOPPED, phase(ist(12, 22, 0), stopped = true))
    }

    @Test fun liveDataSaysWhenItHasLeftYourStation() {
        val now = ist(12, 23, 55)
        assertEquals(TrainPhase.IN_TRANSIT, phase(now, live(TrainFixtures.running, now)))
        val notYet = ist(12, 21, 40)
        assertEquals(TrainPhase.DEPARTING, phase(notYet, live(TrainFixtures.notStarted, notYet).copy(delayMinutes = 0, started = false)))
    }

    @Test fun aLateTrainStaysDepartingUntilItLeaves() {
        val late = train.copy(status = TrainStatusKind.RUNNING_LATE, delayMinutes = 90, newDeparture = LocalTime.of(23, 0))
        assertEquals(TrainPhase.WAITING, phase(ist(12, 21, 40), t = late))
        assertEquals(TrainPhase.DEPARTING, phase(ist(12, 22, 31), t = late))
    }

    @Test fun arrivedCancelledAreOver() {
        val at = ist(13, 4, 50)
        val d = TrainTracking.evaluate(train, live(TrainFixtures.arrived, at), at)
        assertEquals(TrainPhase.ARRIVED, d.phase)
        assertFalse(d.arrivalEstimated)
        assertEquals(TrainPhase.CANCELLED, phase(at, live(TrainFixtures.notStarted, at).copy(cancelled = true)))
        assertEquals(TrainPhase.PARTIALLY_CANCELLED, phase(at, live(TrainFixtures.running, at).copy(partiallyCancelled = true)))
        assertEquals(TrainPhase.DIVERTED, phase(at, live(TrainFixtures.running, at).copy(diverted = true)))
    }

    @Test fun anArrivalWorkedOutFromAScheduleAndALaterAnswerIsMarkedEstimated() {
        val seenAt = ist(12, 23, 55)
        val running = live(TrainFixtures.running, seenAt)
        val after = ist(13, 5, 0)
        val stale = running.copy(updatedAt = after.toEpochMilli())
        val d = TrainTracking.evaluate(train, stale, after)
        assertEquals(TrainPhase.ARRIVED, d.phase)
        assertTrue(d.arrivalEstimated)
        // An old answer is not enough.
        assertEquals(TrainPhase.IN_TRANSIT, phase(after, running))
    }

    @Test fun atMostTwoTripsInAllAndFlightsAreCountedFirst() {
        val a = train
        val b = train.copy(number = "12001")
        val c = train.copy(number = "12002")
        val now = ist(12, 22, 0)
        val ds = TrainTracking.decisions(listOf(a, b, c), emptyMap(), now)
        assertEquals(2, TrainTracking.active(ds).size)
        assertEquals(1, TrainTracking.active(ds, TrainTracking.slotsLeft(1)).size)
        assertEquals(0, TrainTracking.active(ds, TrainTracking.slotsLeft(2)).size)
        assertEquals(0, TrainTracking.slotsLeft(5))
        assertEquals(2, TrainTracking.MAX_TRACKED)
        assertEquals(30_000L, TrainTracking.POLL_MS)
    }

    @Test fun shouldRunAndStopReasons() {
        val now = ist(12, 22, 0)
        val ds = TrainTracking.decisions(listOf(train), emptyMap(), now)
        assertTrue(TrainTracking.shouldRun(true, true, true, ds))
        assertFalse(TrainTracking.shouldRun(false, true, true, ds))
        assertFalse(TrainTracking.shouldRun(true, true, true, ds, slots = 0))
        assertNull(TrainTracking.stopReason(true, true, true, ds, 0))
        assertEquals(StopReason.LIVE_OFF, TrainTracking.stopReason(false, true, true, ds, 0))
        assertEquals(StopReason.TRACKING_OFF, TrainTracking.stopReason(true, false, true, ds, 0))
        assertEquals(StopReason.NOTIFICATIONS_OFF, TrainTracking.stopReason(true, true, false, ds, 0))
        assertEquals(StopReason.TIME_LIMIT, TrainTracking.stopReason(true, true, true, ds, 6 * 3_600_000L))
        assertEquals(StopReason.NOTHING_TO_WATCH, TrainTracking.stopReason(true, true, true, emptyList(), 0))
        assertEquals(30_000L, TrainTracking.pollDelayMs(3))
        assertEquals(300_000L, TrainTracking.pollDelayMs(11))
        assertTrue(TrainTracking.refreshFailed(11) && !TrainTracking.refreshFailed(10))
    }

    @Test fun theAlarmIsTenMinutesBeforeTheNextDeparture() {
        val ds = TrainTracking.decisions(listOf(train), emptyMap(), ist(12, 12, 0))
        assertEquals(ist(12, 21, 20), TrainTracking.nextAlarm(ds, ist(12, 12, 0)))
        assertNull(TrainTracking.nextAlarm(ds, ist(12, 21, 25)))
        val moving = TrainTracking.decisions(listOf(train), emptyMap(), ist(12, 22, 0))
        assertNull(TrainTracking.nextAlarm(moving, ist(12, 22, 0)))
    }
}
