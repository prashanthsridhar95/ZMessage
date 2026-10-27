package com.zyke.message.domain

import com.zyke.message.domain.life.Journeys
import com.zyke.message.domain.life.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneysTest {
    @Test
    fun aTrainTicket_isATrain_withItsRoute() {
        val j = Journeys.of("PNR: 1234567890, Train: 12007 SHATABDI EXP, DOJ: 12-11-2026, From CHENNAI to BENGALURU on 12-11-2026. IRCTC", "IRCTC")
        assertEquals(TravelMode.TRAIN, j.mode)
        assertEquals("CHENNAI", j.from)
        assertEquals("BENGALURU", j.to)
        assertEquals("CHENNAI → BENGALURU", j.route)
    }

    @Test
    fun aFlight_isRecognisedByItsWordsOrAirportCodes() {
        val j = Journeys.of("Your IndiGo flight 6E 123 MAA-BLR is confirmed. PNR AB12CD. Web check-in opens soon.")
        assertEquals(TravelMode.FLIGHT, j.mode)
        assertEquals("MAA", j.from)
        assertEquals("BLR", j.to)
    }

    @Test
    fun busesStaysAndUnknownTrips() {
        assertEquals(TravelMode.BUS, Journeys.of("redBus ticket confirmed. Seat no 12, boarding point Koyambedu").mode)
        assertEquals(TravelMode.HOTEL, Journeys.of("Your hotel booking is confirmed for check-in on 3 Dec").mode)
        val other = Journeys.of("Your booking is confirmed. Booking ID ABC12345")
        assertEquals(TravelMode.OTHER, other.mode)
        assertNull(other.route)
    }
}
