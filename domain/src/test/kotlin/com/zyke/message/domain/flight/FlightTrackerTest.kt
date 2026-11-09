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

class FlightTrackerTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun ms(date: String, time: String): Long = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ist).toInstant().toEpochMilli()
    private fun at(date: String, time: String): Instant = Instant.ofEpochMilli(ms(date, time))
    private fun sms(date: String, time: String, body: String) = FlightSms(body, ms(date, time))

    private val booking = sms("2026-11-05", "10:00", "Booking confirmed. IndiGo flight 6E 2345 Chennai (MAA) to Delhi (DEL) on 12 Nov 2026 departs 14:35, arrives 17:20. PNR: ABC123.")
    private val reminder = sms("2026-11-11", "18:00", "Reminder: IndiGo flight 6E 2345 MAA-DEL departs tomorrow at 14:35. PNR ABC123")

    private fun flights(vararg messages: FlightSms, now: Instant = at("2026-11-12", "09:00")) = FlightTracker.bookings(messages.toList(), now, ist)

    @Test
    fun severalMessagesAboutOneFlightAreOneFlight() {
        val all = flights(booking, reminder)
        assertEquals(1, all.size)
        val f = all.single()
        assertEquals("6E 2345", f.code)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
        assertEquals("ABC123", f.pnr)
        assertEquals(sms("2026-11-11", "18:00", "").at, f.lastMessageAt)
    }

    @Test
    fun theSameNumberOnAnotherDayIsAnotherFlight() {
        val later = sms("2026-11-06", "10:00", "Booking confirmed. IndiGo flight 6E 2345 Delhi (DEL) to Chennai (MAA) on 14 Nov 2026 departs 19:00. PNR: QWE456.")
        assertEquals(2, flights(booking, later).size)
    }

    @Test
    fun aDelayTextMovesTheTimeAndSetsTheStatus() {
        val delay = sms("2026-11-12", "11:00", "IndiGo: Flight 6E 2345 MAA-DEL on 12 Nov is delayed by 45 mins. New departure time 15:20.")
        val f = flights(booking, reminder, delay).single()
        assertEquals(FlightStatusKind.DELAYED, f.status)
        assertEquals(45, f.delayMinutes)
        assertEquals(LocalTime.of(14, 35), f.departure)
        assertEquals(LocalTime.of(15, 20), f.newDeparture)
        val snap = FlightTracker.snapshot(f, null, ist)
        assertEquals(FlightStatusKind.DELAYED, snap.kind)
        assertEquals(45, snap.delayMinutes)
        assertEquals(at("2026-11-12", "15:20"), snap.bestDeparture)
        assertEquals(at("2026-11-12", "14:35"), snap.scheduledDeparture)
        val now = at("2026-11-12", "12:00")
        assertEquals("Delayed 45 min · new 15:20", FlightText.headline(snap, now))
    }

    @Test
    fun delayWithOnlyANewTimeWorksOutTheMinutes() {
        val delay = sms("2026-11-12", "11:00", "IndiGo: Flight 6E 2345 MAA-DEL on 12 Nov has been rescheduled. Revised departure 15:00. Sorry for the delay.")
        val f = flights(booking, delay).single()
        assertEquals(FlightStatusKind.DELAYED, f.status)
        assertEquals(25, f.delayMinutes)
    }

    @Test
    fun datelessCheckInBoardingAndGateTextsJoinTheFlight() {
        val checkIn = sms("2026-11-12", "08:00", "Web check-in is now open for your IndiGo flight 6E 2345 MAA-DEL. PNR ABC123")
        val boarding = sms("2026-11-12", "13:50", "Boarding for IndiGo flight 6E 2345 has started at Gate 12, Terminal 1. Please proceed to the gate.")
        val f = flights(booking, checkIn, boarding).single()
        assertEquals(FlightStatusKind.BOARDING, f.status)
        assertEquals("12", f.gate)
        assertEquals("1", f.terminal)
        assertEquals(1, flights(booking, checkIn).size)
        assertEquals(FlightStatusKind.CHECK_IN, flights(booking, checkIn).single().status)
    }

    @Test
    fun aCancellationWinsUntilRebooked() {
        val cancel = sms("2026-11-12", "07:00", "IndiGo regrets that flight 6E 2345 on 12/11/26 is cancelled. PNR: ABC123")
        assertEquals(FlightStatusKind.CANCELLED, flights(booking, cancel).single().status)
        val rebooked = sms("2026-11-12", "08:00", "Booking confirmed. IndiGo flight 6E 2345 MAA-DEL on 12 Nov 2026 departs 14:35. PNR: ABC123")
        assertEquals(FlightStatusKind.SCHEDULED, flights(booking, cancel, rebooked).single().status)
    }

    @Test
    fun messagesOlderThanAYearAreIgnoredAndOtherTextsAreNot() {
        val old = sms("2024-01-01", "10:00", "Booking confirmed. IndiGo flight 6E 11 DEL-BOM on 5 Jan 2024 departs 10:00. PNR: OLD123")
        val noise = sms("2026-11-12", "09:00", "Your OTP is 123456")
        assertEquals(1, flights(booking, old, noise).size)
    }

    // ---- which flights are today's

    private fun today(now: Instant, live: Map<String, LiveStatus> = emptyMap(), vararg messages: FlightSms): List<BookedFlight> {
        val all = FlightTracker.bookings(messages.toList(), now, ist)
        return FlightTracker.today(all, now, ist, live)
    }

    @Test
    fun onlyTheDayOfTravelShowsTheCard() {
        assertTrue(today(at("2026-11-11", "23:59"), emptyMap(), booking).isEmpty())
        assertEquals(1, today(at("2026-11-12", "00:00"), emptyMap(), booking).size)
        assertEquals(1, today(at("2026-11-12", "14:00"), emptyMap(), booking).size)
        assertTrue(today(at("2026-11-13", "09:00"), emptyMap(), booking).isEmpty())
    }

    @Test
    fun theCardStaysAFewHoursAfterLanding() {
        assertEquals(1, today(at("2026-11-12", "20:00"), emptyMap(), booking).size)
        assertTrue(today(at("2026-11-12", "20:21"), emptyMap(), booking).isEmpty())
    }

    @Test
    fun aDelayedFlightStaysLonger() {
        val delay = sms("2026-11-12", "11:00", "IndiGo: Flight 6E 2345 MAA-DEL on 12 Nov is delayed by 120 mins. New departure time 16:35.")
        assertEquals(1, today(at("2026-11-12", "22:00"), emptyMap(), booking, delay).size)
        assertTrue(today(at("2026-11-12", "22:30"), emptyMap(), booking, delay).isEmpty())
    }

    @Test
    fun liveLandingTimeDecidesWhenItGoes() {
        val live = LiveStatus(FlightStatusKind.LANDED, actualDeparture = at("2026-11-12", "14:50"), actualArrival = at("2026-11-12", "17:40"))
        val key = "6E2345|2026-11-12"
        assertEquals(1, today(at("2026-11-12", "20:30"), mapOf(key to live), booking).size)
        assertTrue(today(at("2026-11-12", "20:41"), mapOf(key to live), booking).isEmpty())
    }

    @Test
    fun anOvernightFlightThatLeftYesterdayIsStillTodayUntilItLands() {
        val night = sms("2026-11-05", "10:00", "Booking confirmed. Air India flight AI 144 DEL-BOM on 12 Nov 2026. Dep 23:30 Arr 01:45. PNR: NIGHT1")
        // 12 Nov 23:30 departure, landing 13 Nov 01:45: still shown at 02:30 on the 13th, gone by 05:00.
        assertEquals(1, today(at("2026-11-13", "02:30"), emptyMap(), night).size)
        assertTrue(today(at("2026-11-13", "05:00"), emptyMap(), night).isEmpty())
        assertEquals(1, today(at("2026-11-12", "21:00"), emptyMap(), night).size)
        assertEquals(LocalTime.of(1, 45), FlightTracker.bookings(listOf(night), at("2026-11-12", "21:00"), ist).single().arrival)
        assertEquals(at("2026-11-13", "01:45"), FlightTracker.scheduledArrival(FlightTracker.bookings(listOf(night), at("2026-11-12", "21:00"), ist).single(), ist))
    }

    @Test
    fun aFlightWithNoTimeStaysForTheWholeDay() {
        val bare = sms("2026-11-05", "10:00", "Booking confirmed. Air India flight AI 101 DEL-BOM on 12 Nov 2026. PNR: BARE12")
        assertEquals(1, today(at("2026-11-12", "23:30"), emptyMap(), bare).size)
        assertTrue(today(at("2026-11-13", "00:00"), emptyMap(), bare).isEmpty())
    }

    @Test
    fun aCancelledFlightStaysForTheDay() {
        val cancel = sms("2026-11-12", "07:00", "IndiGo regrets that flight 6E 2345 on 12/11/26 is cancelled. PNR: ABC123")
        assertEquals(1, today(at("2026-11-12", "23:00"), emptyMap(), booking, cancel).size)
    }

    @Test
    fun theDepartureAirportsClockDecidesTheDay() {
        // Dubai is 1.5 hours behind India: a flight on the 12th leaving DXB at 00:30 is the 12th there while it is still the 11th in India at 02:00 IST... 
        val dxb = sms("2026-11-05", "10:00", "Booking confirmed. Air India Express flight IX 144 DXB-COK on 12 Nov 2026. Dep 00:30 Arr 06:10. PNR: DUB123")
        // 11 Nov 23:30 in Dubai (UTC+4) is 01:00 on the 12th in India, but still the 11th at the airport.
        val inDubaiStillTheEleventh = LocalDate.of(2026, 11, 11).atTime(23, 30).atZone(ZoneId.of("Asia/Dubai")).toInstant()
        assertTrue(today(inDubaiStillTheEleventh, emptyMap(), dxb).isEmpty())
        val inDubaiTheTwelfth = LocalDate.of(2026, 11, 12).atTime(0, 5).atZone(ZoneId.of("Asia/Dubai")).toInstant()
        assertEquals(1, today(inDubaiTheTwelfth, emptyMap(), dxb).size)
    }

    @Test
    fun flightsAreOrderedByDeparture() {
        val early = sms("2026-11-05", "10:00", "Booking confirmed. Akasa Air flight QP 1105 BLR-BOM on 12 Nov 2026 departs 06:45. PNR: AKQ7Z9")
        val list = today(at("2026-11-12", "05:00"), emptyMap(), booking, early)
        assertEquals(listOf("QP 1105", "6E 2345"), list.map { it.code })
    }

    // ---- merging with live data

    private val live = LiveStatus(
        kind = FlightStatusKind.DELAYED,
        scheduledDeparture = at("2026-11-12", "14:35"), estimatedDeparture = at("2026-11-12", "15:05"),
        scheduledArrival = at("2026-11-12", "17:20"), estimatedArrival = at("2026-11-12", "17:50"),
        originCode = "MAA", destinationCode = "DEL", terminal = "4", gate = "B7", arrivalTerminal = "1", baggage = "7",
        delayMinutes = 30, updatedAt = ms("2026-11-12", "12:00")
    )

    @Test
    fun liveDataWinsAndFillsInPlaceTerminalGateAndBelt() {
        val f = flights(booking).single()
        val snap = FlightTracker.snapshot(f, live, ist)
        assertEquals(FlightStatusKind.DELAYED, snap.kind)
        assertEquals(30, snap.delayMinutes)
        assertEquals("Terminal 4 · Gate B7", FlightText.place(snap))
        assertEquals("7", snap.baggage)
        assertEquals(at("2026-11-12", "15:05"), snap.bestDeparture)
        assertEquals(at("2026-11-12", "17:50"), snap.bestArrival)
        assertTrue(snap.isLive)
        assertEquals("IndiGo 6E 2345 · Chennai → Delhi", FlightText.title(snap))
        assertEquals("Delayed 30 min · new 15:05", FlightText.headline(snap, at("2026-11-12", "12:00")))
    }

    @Test
    fun aBoardingTextIsKeptWhenLiveDataOnlySaysScheduled() {
        val boarding = sms("2026-11-12", "13:50", "Boarding for IndiGo flight 6E 2345 has started at Gate 12.")
        val f = flights(booking, boarding).single()
        val scheduled = live.copy(kind = FlightStatusKind.SCHEDULED, estimatedDeparture = null, estimatedArrival = null, delayMinutes = 0, gate = null)
        val snap = FlightTracker.snapshot(f, scheduled, ist)
        assertEquals(FlightStatusKind.BOARDING, snap.kind)
        assertEquals("12", snap.gate)
        assertEquals("Boarding · departs 14:35", FlightText.headline(snap, at("2026-11-12", "14:00")))
    }

    @Test
    fun theWordsForEachState() {
        val f = flights(booking).single()
        val now = at("2026-11-12", "12:25")
        val scheduled = FlightTracker.snapshot(f, null, ist)
        assertEquals("Departs 14:35 · in 2h 10m", FlightText.headline(scheduled, now))
        assertEquals("IndiGo 6E 2345 · Chennai → Delhi", FlightText.title(scheduled))
        assertNull(FlightText.place(scheduled))
        val air = FlightTracker.snapshot(f, live.copy(kind = FlightStatusKind.IN_AIR, actualDeparture = at("2026-11-12", "15:10"), estimatedArrival = at("2026-11-12", "17:50")), ist)
        assertEquals("Lands 17:50 · in 1h 20m", FlightText.headline(air, at("2026-11-12", "16:30")))
        val landed = FlightTracker.snapshot(f, live.copy(kind = FlightStatusKind.LANDED, actualArrival = at("2026-11-12", "17:44")), ist)
        assertEquals("Landed · 17:44", FlightText.headline(landed, at("2026-11-12", "18:00")))
        val cancelled = FlightTracker.snapshot(f, live.copy(kind = FlightStatusKind.CANCELLED), ist)
        assertEquals("Cancelled · check with the airline", FlightText.headline(cancelled, now))
        assertFalse(FlightStatusKind.IN_AIR.isFinal)
        assertTrue(FlightStatusKind.LANDED.isFinal)
    }

    @Test
    fun aFlightWithNoTimeSaysSo() {
        val bare = sms("2026-11-05", "10:00", "Booking confirmed. Air India flight AI 101 DEL-BOM on 12 Nov 2026. PNR: BARE12")
        val snap = FlightTracker.snapshot(flights(bare).single(), null, ist)
        assertEquals("Departure time not in your message", FlightText.headline(snap, at("2026-11-12", "08:00")))
    }

    @Test
    fun arrivalAfterMidnightIsMarked() {
        val night = sms("2026-11-05", "10:00", "Booking confirmed. Air India flight AI 144 DEL-BOM on 12 Nov 2026. Dep 23:30 Arr 01:45. PNR: NIGHT1")
        val snap = FlightTracker.snapshot(flights(night).single(), null, ist)
        assertEquals("01:45 +1", FlightText.arrivalClock(snap))
    }

    @Test
    fun countdownAndUpdatedWords() {
        val now = at("2026-11-12", "12:00")
        assertEquals("in 2h 10m", Countdown.until(now, at("2026-11-12", "14:10")))
        assertEquals("in 2h", Countdown.until(now, at("2026-11-12", "14:00")))
        assertEquals("in 45m", Countdown.until(now, at("2026-11-12", "12:45")))
        assertEquals("in 1m", Countdown.until(now, at("2026-11-12", "12:01")))
        assertEquals("now", Countdown.until(now, now))
        assertEquals("20m ago", Countdown.until(now, at("2026-11-12", "11:40")))
        assertEquals("in 1d 3h", Countdown.until(now, at("2026-11-13", "15:00")))
        assertEquals("Updated just now", FlightText.updated(10_000, 8_000))
        assertEquals("Updated 12 s ago", FlightText.updated(22_000, 10_000))
        assertEquals("Updated 3 min ago", FlightText.updated(200_000, 20_000))
    }

    @Test
    fun progressLine() {
        val f = flights(booking).single()
        val air = FlightTracker.snapshot(f, live.copy(kind = FlightStatusKind.IN_AIR, actualDeparture = at("2026-11-12", "15:00"), estimatedArrival = at("2026-11-12", "17:00")), ist)
        assertEquals(0.5f, FlightText.progress(air, at("2026-11-12", "16:00")), 0.01f)
        assertEquals(0f, FlightText.progress(FlightTracker.snapshot(f, null, ist), at("2026-11-12", "12:00")), 0f)
        assertEquals(1f, FlightText.progress(FlightTracker.snapshot(f, live.copy(kind = FlightStatusKind.LANDED), ist), at("2026-11-12", "18:00")), 0f)
    }
}
