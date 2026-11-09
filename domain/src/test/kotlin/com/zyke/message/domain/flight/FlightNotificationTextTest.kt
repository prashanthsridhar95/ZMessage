package com.zyke.message.domain.flight

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightNotificationTextTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 11, 12)

    private fun at(time: String, day: LocalDate = date, zone: ZoneId = ist): Instant = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun booked(dep: String = "14:35", arr: String = "16:40", from: String = "DEL", to: String = "MAA") = BookedFlight(
        Airlines.byCode("6E")!!, "2345", date, Place(from, Airports.city(from)), Place(to, Airports.city(to)),
        LocalTime.parse(dep), LocalTime.parse(arr), null, null, null
    )

    private fun live(
        kind: FlightStatusKind, estArrival: String? = null, actualArrival: String? = null, actualDeparture: String? = "14:40",
        arrTerminal: String? = null, belt: String? = null, updated: Long = 0L
    ) = LiveStatus(
        kind, scheduledDeparture = at("14:35"), actualDeparture = actualDeparture?.let { at(it) },
        scheduledArrival = at("16:40"), estimatedArrival = estArrival?.let { at(it) }, actualArrival = actualArrival?.let { at(it) },
        arrivalTerminal = arrTerminal, baggage = belt, updatedAt = updated
    )

    private fun snap(l: LiveStatus?, b: BookedFlight = booked()) = FlightTracker.snapshot(b, l, ist)

    @Test fun againstScheduleSaysOnTimeEarlyAndLate() {
        val s = at("16:40")
        assertEquals("on time", FlightNotificationText.againstSchedule(s, at("16:40")))
        assertEquals("on time", FlightNotificationText.againstSchedule(s, at("16:44")))
        assertEquals("on time", FlightNotificationText.againstSchedule(s, at("16:36")))
        assertEquals("5 min late", FlightNotificationText.againstSchedule(s, at("16:45")))
        assertEquals("12 min late", FlightNotificationText.againstSchedule(s, at("16:52")))
        assertEquals("6 min early", FlightNotificationText.againstSchedule(s, at("16:34")))
        assertEquals("1 h late", FlightNotificationText.againstSchedule(s, at("17:40")))
        assertEquals("1 h 5 min late", FlightNotificationText.againstSchedule(s, at("17:45")))
        assertEquals("1 h 20 min early", FlightNotificationText.againstSchedule(s, at("15:20")))
        assertNull(FlightNotificationText.againstSchedule(null, at("16:40")))
        assertNull(FlightNotificationText.againstSchedule(s, null))
    }

    @Test fun inAirNoticeHasRouteLandingTimeBarAndUpdated() {
        val updated = at("15:30").toEpochMilli() + 10_000
        val s = snap(live(FlightStatusKind.IN_AIR, estArrival = "16:52", updated = updated))
        val n = FlightNotificationText.inAir(s, at("15:30"), ist, updated)
        assertEquals("DEL → MAA", n.title)
        assertEquals("In the air · lands 16:52 (12 min late)", n.text)
        assertEquals("Updated 15:30:10", n.subText)
        // 14:40 -> 16:52 is 132 minutes; 50 minutes flown.
        assertEquals(37, n.progress)
    }

    @Test fun inAirOnTimeEarlyAndWithoutALiveUpdate() {
        val onTime = FlightNotificationText.inAir(snap(live(FlightStatusKind.IN_AIR, estArrival = "16:41")), at("15:00"), ist, 1L)
        assertEquals("In the air · lands 16:41 (on time)", onTime.text)
        val early = FlightNotificationText.inAir(snap(live(FlightStatusKind.IN_AIR, estArrival = "16:30")), at("15:00"), ist, 1L)
        assertEquals("In the air · lands 16:30 (10 min early)", early.text)
        val booking = FlightNotificationText.inAir(snap(null), at("15:00"), ist, null)
        assertEquals("From your booking", booking.subText)
        val departed = FlightNotificationText.inAir(snap(live(FlightStatusKind.DEPARTED, estArrival = "16:40")), at("14:45"), ist, 1L)
        assertTrue(departed.text.startsWith("Departed · lands 16:40"))
    }

    @Test fun progressIsAlwaysAVisibleBarBetweenThreeAndNinetySeven() {
        val s = snap(live(FlightStatusKind.IN_AIR, estArrival = "16:40"))
        assertEquals(3, FlightNotificationText.inAir(s, at("14:40"), ist, 1L).progress)
        assertEquals(97, FlightNotificationText.inAir(s, at("20:00"), ist, 1L).progress)
    }

    @Test fun refreshFailedSaysSoWithTheLastUpdate() {
        val updated = at("15:32").toEpochMilli()
        val s = snap(live(FlightStatusKind.IN_AIR, estArrival = "16:40"))
        val n = FlightNotificationText.inAir(s, at("15:50"), ist, updated, refreshFailed = true)
        assertEquals("Can't refresh · last update 15:32", n.text)
        assertEquals("In the air · lands 16:40 (on time)", n.subText)
        assertEquals("Can't refresh", FlightNotificationText.cantRefresh(null, ist))
    }

    @Test fun landedEarlyWithTerminalAndBelt() {
        val s = snap(live(FlightStatusKind.LANDED, actualArrival = "16:34", arrTerminal = "1", belt = "4"))
        val n = FlightNotificationText.landed(s)
        assertEquals("Landed · DEL → MAA at 16:34", n.title)
        assertEquals("6 min early · Terminal 1 · Belt 4", n.text)
        assertEquals("Landed 6 min early at 16:34 · Terminal 1 · Belt 4", FlightNotificationText.landedOneLine(s))
        assertNull(n.progress)
    }

    @Test fun landedOnTimeAndLateAndWithoutExtras() {
        val onTime = snap(live(FlightStatusKind.LANDED, actualArrival = "16:42"))
        assertEquals("On time", FlightNotificationText.landed(onTime).text)
        assertEquals("Landed on time at 16:42", FlightNotificationText.landedOneLine(onTime))
        val late = snap(live(FlightStatusKind.LANDED, actualArrival = "18:05"))
        assertEquals("1 h 25 min late", FlightNotificationText.landed(late).text)
        assertEquals("Landed · DEL → MAA at 18:05", FlightNotificationText.landed(late).title)
        val noSchedule = snap(LiveStatus(FlightStatusKind.LANDED, actualArrival = at("16:34")), booked().copy(arrival = null))
        assertEquals("Landed", FlightNotificationText.landed(noSchedule).text)
    }

    @Test fun landingWorkedOutFromTheClockSaysAround() {
        val s = snap(live(FlightStatusKind.DEPARTED, estArrival = "16:40"))
        assertEquals("Landed · DEL → MAA around 16:40", FlightNotificationText.landed(s, estimated = true).title)
    }

    @Test fun cancelledAndDiverted() {
        val c = FlightNotificationText.cancelled(snap(live(FlightStatusKind.CANCELLED)))
        assertEquals("Cancelled · DEL → MAA", c.title)
        assertEquals("Check with the airline for another flight", c.text)
        val d = FlightNotificationText.diverted(snap(live(FlightStatusKind.DIVERTED)))
        assertEquals("Diverted · DEL → MAA", d.title)
    }

    @Test fun unknownTimesFallBackToWhatIsKnown() {
        val noTimes = BookedFlight(Airlines.byCode("6E")!!, "2345", date, Place(null, "Delhi"), null, null, null, null, null, null)
        val s = snap(null, noTimes)
        assertEquals("Delhi", FlightNotificationText.route(s))
        val n = FlightNotificationText.inAir(s, at("15:00"), ist, null)
        assertEquals("In the air", n.text)
        val bare = BookedFlight(Airlines.byCode("6E")!!, "2345", date, null, null, null, null, null, null, null)
        assertEquals("Your flight 6E 2345", FlightNotificationText.route(snap(null, bare)))
        assertTrue(FlightNotificationText.reminder(snap(null, bare), at("09:00")).text.startsWith("Your flight is today"))
    }

    @Test fun timeZonesAreEachAirportsOwn() {
        val dxb = ZoneId.of("Asia/Dubai")
        val b = BookedFlight(
            Airlines.byCode("EK")!!, "517", date, Place("DEL", "Delhi"), Place("DXB", "Dubai"),
            LocalTime.of(14, 35), LocalTime.of(17, 5), null, null, null
        )
        val s = snap(null, b)
        assertEquals("DEL → DXB", FlightNotificationText.route(s))
        // The arrival is read on Dubai's clock.
        assertEquals("17:05", FlightNotificationText.arrivalClock(s))
        assertEquals(dxb, s.arrivalZone)
        // The phone's zone only matters for "Updated": 14:30 IST is 09:00 UTC.
        assertEquals("09:00:00", FlightNotificationText.updatedClock(at("14:30").toEpochMilli(), ZoneId.of("UTC")))
    }

    @Test fun overnightFlightsShowPlusOne() {
        val b = BookedFlight(
            Airlines.byCode("6E")!!, "2345", date, Place("DEL", "Delhi"), Place("MAA", "Chennai"),
            LocalTime.of(23, 30), LocalTime.of(2, 15), null, null, null
        )
        val l = LiveStatus(
            FlightStatusKind.IN_AIR, scheduledDeparture = at("23:30"), actualDeparture = at("23:30"),
            scheduledArrival = at("02:15", date.plusDays(1)), estimatedArrival = at("02:15", date.plusDays(1)), updatedAt = 1L
        )
        val s = snap(l, b)
        val n = FlightNotificationText.inAir(s, at("00:30", date.plusDays(1)), ist, 1L)
        assertEquals("In the air · lands 02:15 +1 (on time)", n.text)
        val landed = LiveStatus(
            FlightStatusKind.LANDED, scheduledDeparture = at("23:30"), actualDeparture = at("23:30"),
            scheduledArrival = at("02:15", date.plusDays(1)), actualArrival = at("02:05", date.plusDays(1)), updatedAt = 1L
        )
        assertEquals("Landed · DEL → MAA at 02:05 +1", FlightNotificationText.landed(snap(landed, b)).title)
    }

    @Test fun departingNoticeAndReminders() {
        val s = snap(null)
        val n = FlightNotificationText.departing(s, at("14:10"), ist, null)
        assertEquals("Departs 14:35 (on time) · in 25m", n.text)
        assertEquals(0, n.progress)
        assertEquals("Departs 14:35 · in 25m · lands 16:40 (from your booking)", FlightNotificationText.reminder(s, at("14:10")).text)
        assertTrue(FlightNotificationText.tapToStart(s, at("14:25")).text.endsWith("tap to start live tracking"))
        assertEquals("Tracking paused", FlightNotificationText.paused().title)
        assertEquals("Open Zyke to resume", FlightNotificationText.paused().text)
        assertEquals("Flight in progress", FlightNotificationText.PUBLIC_TITLE)
    }

    @Test fun unconfirmedLandingEndsWithWhenItWasDue() {
        val n = FlightNotificationText.unconfirmed(snap(null))
        assertEquals("Tracking ended · DEL → MAA", n.title)
        assertEquals("Landing not confirmed; was due 16:40", n.text)
    }
}
