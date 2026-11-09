package com.zyke.message.domain.flight

import com.zyke.message.domain.classification.KnownEntityDomains
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightCardModelTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 11, 12)
    private fun at(time: String): Instant = date.atTime(LocalTime.parse(time)).atZone(ist).toInstant()

    private fun booked(code: String = "6E", terminal: String? = null, gate: String? = null, status: FlightStatusKind = FlightStatusKind.SCHEDULED) = BookedFlight(
        Airlines.byCode(code)!!, "2345", date, Place("DEL", "Delhi"), Place("MAA", "Chennai"),
        LocalTime.parse("18:40"), LocalTime.parse("21:25"), null, terminal, gate, status
    )

    private fun live(kind: FlightStatusKind, estDep: String? = null, actDep: String? = null, estArr: String? = null, actArr: String? = null, delay: Int = 0,
                     terminal: String? = null, gate: String? = null, belt: String? = null, updated: Long = 0L) = LiveStatus(
        kind, scheduledDeparture = at("18:40"), estimatedDeparture = estDep?.let { at(it) }, actualDeparture = actDep?.let { at(it) },
        scheduledArrival = at("21:25"), estimatedArrival = estArr?.let { at(it) }, actualArrival = actArr?.let { at(it) },
        terminal = terminal, gate = gate, baggage = belt, delayMinutes = delay, updatedAt = updated
    )

    private fun model(l: LiveStatus?, now: String, b: BookedFlight = booked(), liveOn: Boolean = l != null): FlightCardModel =
        FlightCardModel.of(FlightTracker.snapshot(b, l, ist), at(now), liveOn)

    @Test fun scheduledSaysWhenItLeavesAndOnlyShowsKnownChips() {
        val m = model(null, "16:30", booked(terminal = "1"))
        assertEquals("DEL", m.origin.code); assertEquals("Delhi", m.origin.city); assertEquals("18:40", m.origin.time)
        assertEquals("21:25", m.destination.time)
        assertNull(m.origin.was)
        assertEquals("Departs in 2h 10m", m.supporting)
        assertEquals(listOf("Terminal 1"), m.chips)
        assertEquals("From your message", m.caption)
        assertEquals("2h 45m", m.duration)
        assertEquals("6E 2345 · DEL → MAA · 18:40", m.summary)
        assertEquals(0f, m.progress, 0f)
    }

    @Test fun noTerminalOrGateMeansNoChips() {
        assertTrue(model(null, "16:30").chips.isEmpty())
        assertTrue(model(live(FlightStatusKind.SCHEDULED, updated = at("16:29").toEpochMilli()), "16:30").chips.isEmpty())
    }

    @Test fun delayedStrikesTheScheduleAndEmphasisesTheNewTime() {
        val m = model(live(FlightStatusKind.DELAYED, estDep = "19:15", estArr = "22:00", delay = 35, updated = at("16:30").toEpochMilli()), "18:00")
        assertEquals("19:15", m.origin.time); assertEquals("18:40", m.origin.was); assertEquals(TimeShift.LATER, m.origin.shift)
        assertEquals("22:00", m.destination.time); assertEquals("21:25", m.destination.was)
        assertEquals("Delayed 35 min · departs in 1h 15m", m.supporting)
        assertEquals("Updated 1 h ago", m.caption)
        assertTrue(m.spoken.contains("Departs 19:15, was 18:40"))
    }

    @Test fun boardingPutsTheGateInTheLineNotInAChip() {
        val m = model(live(FlightStatusKind.BOARDING, gate = "14", terminal = "1"), "18:20")
        assertEquals("Boarding · Gate 14", m.supporting)
        assertEquals(listOf("Terminal 1"), m.chips)
        assertEquals("Boarding now", model(live(FlightStatusKind.BOARDING), "18:20").supporting)
    }

    @Test fun inTheAirShowsProgressAndTimeToLanding() {
        val m = model(live(FlightStatusKind.IN_AIR, actDep = "18:45", estArr = "21:25"), "20:15", booked())
        assertEquals("Lands in 1h 10m", m.supporting)
        assertTrue(m.progress > 0.4f && m.progress < 0.7f)
        assertTrue(m.showPlane)
        assertEquals("Landing soon", model(live(FlightStatusKind.IN_AIR, actDep = "18:45", estArr = "21:25"), "21:30").supporting)
    }

    @Test fun landedSaysEarlyOnTimeOrLateAndShowsBeltChips() {
        val early = model(live(FlightStatusKind.LANDED, actDep = "18:42", actArr = "21:19", belt = "4"), "21:30")
        assertEquals("Landed 6 min early", early.supporting)
        assertEquals(TimeShift.EARLIER, early.destination.shift)
        assertEquals("21:19", early.destination.time); assertEquals("21:25", early.destination.was)
        assertEquals(listOf("Belt 4"), early.chips)
        assertEquals(1f, early.progress, 0f)
        assertEquals("Landed on time", model(live(FlightStatusKind.LANDED, actDep = "18:40", actArr = "21:27"), "21:30").supporting)
        assertEquals("Landed 12 min late", model(live(FlightStatusKind.LANDED, actDep = "18:50", actArr = "21:37"), "21:40").supporting)
    }

    @Test fun cancelledHasNoPlaneNoChipsAndNoStruckTimes() {
        val m = model(live(FlightStatusKind.CANCELLED, terminal = "1", gate = "3"), "17:00")
        assertFalse(m.showPlane); assertTrue(m.chips.isEmpty())
        assertNull(m.origin.was)
        assertEquals("Cancelled · check with the airline", m.supporting)
    }

    @Test fun unknownPlacesFallBackWithoutEmptyText() {
        val b = booked().copy(origin = null, destination = Place(null, "Somewhere"), departure = null, arrival = null)
        val m = FlightCardModel.of(FlightTracker.snapshot(b, null, ist), at("10:00"), false)
        assertEquals("—", m.origin.code); assertNull(m.origin.city); assertNull(m.origin.time)
        assertEquals("Somewhere", m.destination.code); assertNull(m.destination.city)
        assertEquals("Departure time not in your message", m.supporting)
        assertEquals("6E 2345", m.summary)
        assertNull(m.duration)
    }

    @Test fun spokenSummaryIsOneReadableSentenceGroup() {
        val m = model(live(FlightStatusKind.IN_AIR, actDep = "18:45", estArr = "21:25", terminal = "1", updated = at("20:14").toEpochMilli()), "20:15")
        assertEquals("IndiGo 6E 2345, In the air. Delhi to Chennai. Departed 18:45, was 18:40, arrives 21:25. Lands in 1h 10m. Terminal 1. Updated 1 min ago", m.spoken)
    }

    @Test fun finishedFlightsGoAfterTheOnesStillToCome() {
        val landed = model(live(FlightStatusKind.LANDED, actDep = "18:42", actArr = "21:19"), "21:30", booked("AI"))
        val soon = model(null, "16:30", booked("6E"))
        assertEquals(listOf("6E", "AI"), FlightCardModel.featuredFirst(listOf(landed, soon)).map { it.airlineCode })
    }

    @Test fun everyAirlineHasALogoSiteOrADeliberateNone() {
        val codes = Airlines.list().map { it.code }.toSet()
        val withSite = codes.filter { Airlines.logoDomain(it) != null }.toSet()
        assertEquals("every airline is either mapped or listed as having none", codes, withSite + Airlines.withoutLogo)
        assertTrue("the main Indian airlines have sites", listOf("6E", "AI", "UK", "SG", "QP", "IX").all { Airlines.logoDomain(it) != null })
        assertEquals("goindigo.in", Airlines.logoDomain("6e"))
        assertNull(Airlines.logoDomain("ZZ"))
        Airlines.logoDomains.forEach {
            assertTrue("$it looks like a bare website name", Regex("[a-z0-9.-]+\\.[a-z]{2,}").matches(it))
            assertTrue(KnownEntityDomains.isListed(it))
        }
        assertFalse(KnownEntityDomains.isListed("evil.example"))
    }
}
