package com.zyke.message.domain.flight

import com.zyke.message.domain.demo.DemoData
import com.zyke.message.domain.demo.DemoFlights
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoFlightTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun flightsAt(at: ZonedDateTime, zone: ZoneId = ist): Pair<Instant, List<BookedFlight>> {
        val now = at.toInstant()
        val data = DemoData.build(now.toEpochMilli(), zone)
        val booked = FlightTracker.bookings(data.messages.map { FlightSms(it.body, it.date) }, now, zone)
        return now to FlightTracker.today(booked, now, zone)
    }

    @Test
    fun theDemoHasOneFlightToday_leavingAboutTwoHoursAhead() {
        val (now, today) = flightsAt(ZonedDateTime.of(2026, 11, 5, 14, 30, 0, 0, ist))
        val f = today.single()
        assertEquals("6E 2345", f.code)
        assertEquals("MAA", f.origin?.code)
        assertEquals("DEL", f.destination?.code)
        val snap = FlightTracker.snapshot(f, null, ist)
        assertEquals("Departs 16:40 · in 2h 10m", FlightText.headline(snap, now))
    }

    @Test
    fun theDemoFlightIsThereWhicheverZoneThePhoneIsIn_andNearMidnight() {
        listOf(ZoneId.of("America/New_York"), ZoneId.of("Asia/Dubai"), ZoneId.of("UTC")).forEach { zone ->
            listOf(3, 12, 21, 22, 23).forEach { hour ->
                val (_, today) = flightsAt(ZonedDateTime.of(2026, 11, 5, hour, 20, 0, 0, ist).withZoneSameInstant(zone), zone)
                assertEquals("$zone $hour:20", 1, today.size)
            }
        }
    }

    @Test
    fun theMadeUpStatusFollowsTheClockAndUpdatesEveryTime() {
        val (now, today) = flightsAt(ZonedDateTime.of(2026, 11, 5, 14, 30, 0, 0, ist))
        val f = today.single()
        val before = DemoFlights.live(f, now.toEpochMilli(), ist)
        assertEquals(FlightStatusKind.DELAYED, before.kind)
        assertEquals(25, before.delayMinutes)
        assertEquals(now.toEpochMilli(), before.updatedAt)
        assertNotEquals(before.updatedAt, DemoFlights.live(f, now.toEpochMilli() + 30_000, ist).updatedAt)
        assertEquals(FlightStatusKind.IN_AIR, DemoFlights.live(f, now.plusSeconds(3 * 3600).toEpochMilli(), ist).kind)
        assertEquals(FlightStatusKind.LANDED, DemoFlights.live(f, now.plusSeconds(6 * 3600).toEpochMilli(), ist).kind)
        assertTrue(FlightTracker.snapshot(f, before, ist).isLive)
    }
}
