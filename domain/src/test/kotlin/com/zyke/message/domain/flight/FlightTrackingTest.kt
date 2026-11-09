package com.zyke.message.domain.flight

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightTrackingTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 11, 12)
    private fun at(time: String, day: LocalDate = date): Instant = day.atTime(LocalTime.parse(time)).atZone(ist).toInstant()

    private fun flight(number: String = "2345", dep: String? = "14:35", arr: String? = "16:40", day: LocalDate = date) = BookedFlight(
        Airlines.byCode("6E")!!, number, day, Place("DEL", "Delhi"), Place("MAA", "Chennai"),
        dep?.let { LocalTime.parse(it) }, arr?.let { LocalTime.parse(it) }, null, null, null
    )

    private fun live(
        kind: FlightStatusKind, updated: String = "14:50", depActual: String? = "14:40", arrEst: String? = "16:40", arrActual: String? = null
    ) = LiveStatus(
        kind, scheduledDeparture = at("14:35"), actualDeparture = depActual?.let { at(it) }, scheduledArrival = at("16:40"),
        estimatedArrival = arrEst?.let { at(it) }, actualArrival = arrActual?.let { at(it) }, updatedAt = at(updated).toEpochMilli()
    )

    private fun phase(f: BookedFlight, l: LiveStatus?, now: String, stopped: Boolean = false) =
        FlightTracking.evaluate(f, l, at(now), ist, stopped).phase

    @Test fun waitingUntilHalfAnHourBeforeDeparture() {
        assertEquals(TrackPhase.WAITING, phase(flight(), null, "13:59"))
        assertEquals(TrackPhase.DEPARTING, phase(flight(), null, "14:05"))
        assertEquals(TrackPhase.DEPARTING, phase(flight(), null, "14:34"))
    }

    @Test fun inTheAirFromTheBookedTimeWhenThereIsNoLiveData() {
        assertEquals(TrackPhase.IN_AIR, phase(flight(), null, "14:35"))
        assertEquals(TrackPhase.IN_AIR, phase(flight(), null, "16:39"))
    }

    @Test fun liveDataDecidesOverTheClock() {
        // Delayed: the source says it has not left, though the booked time has passed.
        val delayed = live(FlightStatusKind.DELAYED, depActual = null).copy(estimatedDeparture = at("15:20"))
        assertEquals(TrackPhase.DEPARTING, phase(flight(), delayed, "14:50"))
        assertEquals(TrackPhase.DEPARTING, phase(flight(), live(FlightStatusKind.BOARDING, depActual = null), "14:36"))
        assertEquals(TrackPhase.IN_AIR, phase(flight(), live(FlightStatusKind.IN_AIR), "14:50"))
        assertEquals(TrackPhase.IN_AIR, phase(flight(), live(FlightStatusKind.DEPARTED), "14:42"))
    }

    @Test fun aLateFlightIsNotWatchedUntilNearItsNewTime() {
        val delayed = live(FlightStatusKind.DELAYED, depActual = null).copy(estimatedDeparture = at("17:00"), estimatedArrival = at("19:00"))
        assertEquals(TrackPhase.WAITING, phase(flight(), delayed, "14:40"))
        assertEquals(TrackPhase.DEPARTING, phase(flight(), delayed, "16:35"))
    }

    @Test fun landedCancelledDivertedSettleIt() {
        assertEquals(TrackPhase.LANDED, phase(flight(), live(FlightStatusKind.LANDED, arrActual = "16:34", arrEst = null), "16:35"))
        assertEquals(TrackPhase.CANCELLED, phase(flight(), live(FlightStatusKind.CANCELLED, depActual = null), "13:00"))
        assertEquals(TrackPhase.DIVERTED, phase(flight(), live(FlightStatusKind.DIVERTED), "15:30"))
    }

    @Test fun aCancelledBookingTextIsCancelled() {
        assertEquals(TrackPhase.CANCELLED, phase(flight().copy(status = FlightStatusKind.CANCELLED), null, "14:00"))
    }

    @Test fun landingIsWorkedOutOnlyWhenAnAnswerAfterTheArrivalNoLongerSaysInTheAir() {
        // Arrival time passed, but the answer is older than that: still in the air.
        assertEquals(TrackPhase.IN_AIR, phase(flight(), live(FlightStatusKind.IN_AIR, updated = "16:30"), "16:45"))
        // Still says in the air after the arrival time: keep tracking until the cut-off.
        assertEquals(TrackPhase.IN_AIR, phase(flight(), live(FlightStatusKind.IN_AIR, updated = "16:44"), "16:45"))
        // Source lost the aircraft (departed, not live) and an answer after the arrival time confirms it.
        val d = FlightTracking.evaluate(flight(), live(FlightStatusKind.DEPARTED, updated = "16:44"), at("16:45"), ist)
        assertEquals(TrackPhase.LANDED, d.phase)
        assertTrue(d.landingEstimated)
        // An older answer is not enough.
        assertEquals(TrackPhase.IN_AIR, phase(flight(), live(FlightStatusKind.DEPARTED, updated = "16:00"), "16:45"))
    }

    @Test fun trackingGivesUpThirtyMinutesAfterTheLanding() {
        assertEquals(TrackPhase.IN_AIR, phase(flight(), live(FlightStatusKind.IN_AIR, updated = "17:09"), "17:09"))
        assertEquals(TrackPhase.GAVE_UP, phase(flight(), live(FlightStatusKind.IN_AIR, updated = "17:09"), "17:11"))
        assertEquals(TrackPhase.GAVE_UP, phase(flight(), null, "17:11"))
        assertEquals(at("17:10"), FlightTracking.cutoff(FlightTracker.snapshot(flight(), null, ist)))
    }

    @Test fun aFlightWithNoTimesIsWaitingUntilLiveDataGivesOne() {
        val f = flight(dep = null, arr = null)
        assertEquals(TrackPhase.WAITING, phase(f, null, "15:00"))
        assertEquals(TrackPhase.IN_AIR, phase(f, live(FlightStatusKind.IN_AIR), "15:00"))
    }

    @Test fun stoppedByThePersonIsStopped() {
        assertEquals(TrackPhase.STOPPED, phase(flight(), live(FlightStatusKind.IN_AIR), "15:00", stopped = true))
    }

    @Test fun overnightFlightIsTrackedAcrossMidnight() {
        val f = flight(dep = "23:30", arr = "02:15")
        val l = LiveStatus(FlightStatusKind.IN_AIR, scheduledDeparture = at("23:30"), actualDeparture = at("23:30"),
            scheduledArrival = at("02:15", date.plusDays(1)), estimatedArrival = at("02:15", date.plusDays(1)), updatedAt = at("00:40", date.plusDays(1)).toEpochMilli())
        assertEquals(TrackPhase.IN_AIR, FlightTracking.evaluate(f, l, at("00:40", date.plusDays(1)), ist).phase)
        assertEquals(TrackPhase.IN_AIR, FlightTracking.evaluate(f, null, at("01:00", date.plusDays(1)), ist).phase)
        assertEquals(TrackPhase.GAVE_UP, FlightTracking.evaluate(f, null, at("02:50", date.plusDays(1)), ist).phase)
    }

    @Test fun atMostTwoFlightsAreTrackedEarliestFirst() {
        val flights = listOf(flight("1", "14:50", "16:50"), flight("2", "14:35", "16:40"), flight("3", "14:40", "16:45"))
        val ds = FlightTracking.decisions(flights, emptyMap(), at("14:55"), ist)
        val active = FlightTracking.active(ds)
        assertEquals(listOf("2", "3"), active.map { it.flight.number })
        assertEquals(2, FlightTracking.limit(ds).size)
    }

    @Test fun pollingIsEveryThirtySecondsThenSlowAfterTenFailures() {
        assertEquals(30_000L, FlightTracking.pollDelayMs(0))
        assertEquals(30_000L, FlightTracking.pollDelayMs(10))
        assertEquals(300_000L, FlightTracking.pollDelayMs(11))
        assertFalse(FlightTracking.refreshFailed(10))
        assertTrue(FlightTracking.refreshFailed(11))
    }

    @Test fun stopReasonsInOrder() {
        val active = FlightTracking.decisions(listOf(flight()), emptyMap(), at("14:50"), ist)
        val none = FlightTracking.decisions(listOf(flight()), emptyMap(), at("12:00"), ist)
        assertNull(FlightTracking.stopReason(true, true, true, active, 1_000))
        assertEquals(StopReason.LIVE_OFF, FlightTracking.stopReason(false, true, true, active, 0))
        assertEquals(StopReason.TRACKING_OFF, FlightTracking.stopReason(true, false, true, active, 0))
        assertEquals(StopReason.NOTIFICATIONS_OFF, FlightTracking.stopReason(true, true, false, active, 0))
        assertEquals(StopReason.TIME_LIMIT, FlightTracking.stopReason(true, true, true, active, FlightTracking.MAX_RUN_MS))
        assertEquals(StopReason.NOTHING_TO_WATCH, FlightTracking.stopReason(true, true, true, none, 0))
        assertTrue(FlightTracking.shouldRun(true, true, true, active))
        assertFalse(FlightTracking.shouldRun(true, true, true, none))
        assertFalse(FlightTracking.shouldRun(false, true, true, active))
    }

    @Test fun theAlarmIsTenMinutesBeforeTheNextDeparture() {
        val flights = listOf(flight("1", "18:00", "20:00"), flight("2", "14:35", "16:40"))
        val ds = FlightTracking.decisions(flights, emptyMap(), at("09:00"), ist)
        assertEquals(at("14:25"), FlightTracking.nextAlarm(ds, at("09:00")))
        // Once that one is within its lead the alarm is for the next flight; a flight already in the air has no alarm.
        val later = FlightTracking.decisions(flights, emptyMap(), at("14:30"), ist)
        assertEquals(at("17:50"), FlightTracking.nextAlarm(later, at("14:30")))
        val air = FlightTracking.decisions(listOf(flight()), emptyMap(), at("15:00"), ist)
        assertNull(FlightTracking.nextAlarm(air, at("15:00")))
    }

    @Test fun theAlarmFollowsTheDelayedTime() {
        val delayed = live(FlightStatusKind.DELAYED, depActual = null).copy(estimatedDeparture = at("15:35"))
        val ds = FlightTracking.decisions(listOf(flight()), mapOf(flight().key to delayed), at("12:00"), ist)
        assertEquals(at("15:25"), FlightTracking.nextAlarm(ds, at("12:00")))
    }

    @Test fun aStoppedFlightHasNoAlarm() {
        val ds = FlightTracking.decisions(listOf(flight()), emptyMap(), at("12:00"), ist, stopped = setOf(flight().key))
        assertNull(FlightTracking.nextAlarm(ds, at("12:00")))
    }

    @Test fun timeZonesUseTheDepartureAirportsClock() {
        // Dubai is 1.5 h behind India: a 14:35 Dubai departure is 16:05 in Delhi.
        val dxb = BookedFlight(Airlines.byCode("EK")!!, "517", date, Place("DXB", "Dubai"), Place("DEL", "Delhi"),
            LocalTime.of(14, 35), LocalTime.of(19, 0), null, null, null)
        val dubai = ZoneId.of("Asia/Dubai")
        val now = date.atTime(14, 10).atZone(dubai).toInstant()
        assertEquals(TrackPhase.DEPARTING, FlightTracking.evaluate(dxb, null, now, ist).phase)
        assertEquals(TrackPhase.WAITING, FlightTracking.evaluate(dxb, null, date.atTime(13, 0).atZone(dubai).toInstant(), ist).phase)
    }
}
