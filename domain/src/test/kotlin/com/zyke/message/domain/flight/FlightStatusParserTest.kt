package com.zyke.message.domain.flight

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightStatusParserTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(time: String, day: String = "2026-11-12"): Instant = LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(ist).toInstant()

    private val flight = BookedFlight(
        Airlines.byCode("6E")!!, "2345", LocalDate.of(2026, 11, 12), Place("MAA", "Chennai"), Place("DEL", "Delhi"),
        LocalTime.of(14, 35), LocalTime.of(17, 20), "ABC123", null, null
    )

    private fun parse(json: String) = FlightStatusParser.parse(json, flight, fetchedAt = 123_456L, zone = ist)
    private fun found(json: String): LiveStatus = (parse(json) as FlightLookup.Found).status

    @Test
    fun theQueryIsAirlineAndNumberOnly() {
        assertEquals("6E2345", FlightStatusParser.query(flight.airline, flight.number))
    }

    @Test
    fun scheduledPicksTheLegOnTheDayOfTravel() {
        val s = found(FlightFixtures.scheduled)
        assertEquals(FlightStatusKind.SCHEDULED, s.kind)
        assertEquals(at("14:35"), s.scheduledDeparture)
        assertEquals(at("17:20"), s.scheduledArrival)
        assertEquals("MAA", s.originCode)
        assertEquals("DEL", s.destinationCode)
        assertEquals("1", s.terminal)
        assertEquals("12", s.gate)
        assertEquals("3", s.arrivalTerminal)
        assertEquals("7", s.baggage)
        assertEquals(0, s.delayMinutes)
        assertEquals(123_456L, s.updatedAt)
    }

    @Test
    fun delayedHasMinutesAndEstimates() {
        val s = found(FlightFixtures.delayed)
        assertEquals(FlightStatusKind.DELAYED, s.kind)
        assertEquals(30, s.delayMinutes)
        assertEquals(at("15:05"), s.estimatedDeparture)
        assertEquals(at("17:50"), s.estimatedArrival)
    }

    @Test
    fun inTheAirLandedAndCancelled() {
        val air = found(FlightFixtures.in_air)
        assertEquals(FlightStatusKind.IN_AIR, air.kind)
        assertEquals(at("15:10"), air.actualDeparture)
        assertEquals(35, air.delayMinutes)
        val landed = found(FlightFixtures.landed)
        assertEquals(FlightStatusKind.LANDED, landed.kind)
        assertEquals(at("17:44"), landed.actualArrival)
        assertEquals(FlightStatusKind.CANCELLED, found(FlightFixtures.cancelled).kind)
    }

    @Test
    fun noLegOnThatDayIsNotFound() {
        assertEquals(FlightLookup.NotFound, parse(FlightFixtures.otherday))
        assertEquals(FlightLookup.NotFound, parse("""{"result":{"response":{"data":[]}}}"""))
    }

    @Test
    fun anythingElseIsUnreadableNeverAnException() {
        assertEquals(FlightLookup.Unreadable, parse(""))
        assertEquals(FlightLookup.Unreadable, parse("<html><body>Access denied</body></html>"))
        assertEquals(FlightLookup.Unreadable, parse("""{"result":{"response":{"error":"x"}}}"""))
        assertEquals(FlightLookup.Unreadable, parse("""[1,2,3]"""))
        assertEquals(FlightLookup.Unreadable, parse("""{"result":"nope"}"""))
    }

    @Test
    fun aDifferentFlightNumberInTheReplyIsIgnored() {
        assertEquals(FlightLookup.NotFound, parse(FlightFixtures.scheduled.replace("\"default\":\"6E2345\"", "\"default\":\"6E9999\"")))
    }

    @Test
    fun theRepliedTimesFeedTheSnapshot() {
        val snap = FlightTracker.snapshot(flight, found(FlightFixtures.delayed), ist)
        assertEquals("Delayed 30 min · new 15:05", FlightText.headline(snap, at("12:00")))
        assertTrue(snap.isLive)
    }
}
