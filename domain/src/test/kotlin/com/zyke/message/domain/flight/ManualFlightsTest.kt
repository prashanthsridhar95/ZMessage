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

class ManualFlightsTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 12)
    private val indigo = Airlines.byCode("6E")!!
    private fun at(time: String, day: LocalDate = today): Instant = day.atTime(LocalTime.parse(time)).atZone(ist).toInstant()

    private fun manual(id: String = "m1", number: String = "2345", day: LocalDate = today, dep: String? = null, arr: String? = null, note: String? = null) =
        ManualFlight(id, "6E", number, day, note, null, null, dep?.let { LocalTime.parse(it) }, arr?.let { LocalTime.parse(it) }, false, 1L)

    @Test fun formChecksFieldByField() {
        fun check(a: Airline? = indigo, n: String = "2345", d: LocalDate? = today, p: String = "") = ManualFlightRules.check(a, n, d, p, today)
        assertEquals("airline", (check(a = null) as ManualFlightCheck.Problem).field)
        assertEquals("number", (check(n = "") as ManualFlightCheck.Problem).field)
        assertEquals("number", (check(n = "12345") as ManualFlightCheck.Problem).field)
        assertEquals("number", (check(n = "ABC") as ManualFlightCheck.Problem).field)
        assertEquals("number", (check(n = "0") as ManualFlightCheck.Problem).field)
        assertEquals("date", (check(d = null) as ManualFlightCheck.Problem).field)
        assertEquals("date", (check(d = today.minusDays(2)) as ManualFlightCheck.Problem).field)
        assertEquals("date", (check(d = today.plusDays(400)) as ManualFlightCheck.Problem).field)
        assertEquals("pnr", (check(p = "AB-123") as ManualFlightCheck.Problem).field)
        assertEquals("pnr", (check(p = "ABCDEFGHIJK") as ManualFlightCheck.Problem).field)
        val ok = check(n = " 6e 0123 ", p = "ab c123") as ManualFlightCheck.Ok
        assertEquals("123", ok.number)
        assertEquals("ABC123", ok.pnrNote)
        assertNull((check(p = "") as ManualFlightCheck.Ok).pnrNote)
        // Yesterday is allowed: a flight that left after midnight.
        assertTrue(check(d = today.minusDays(1)) is ManualFlightCheck.Ok)
    }

    @Test fun numberAcceptsTheAirlineCodeTypedToo() {
        assertEquals("2345", ManualFlightRules.numberDigits("6E2345", "6E"))
        assertEquals("2345", ManualFlightRules.numberDigits("6E 2345", "6E"))
        assertNull(ManualFlightRules.numberProblem("6E 2345", "6E"))
        assertEquals("Digits only, like 2345", ManualFlightRules.numberProblem("23x5", "6E"))
    }

    @Test fun airlineSearchMatchesNameAndCode() {
        assertTrue(ManualFlightRules.airlines("indi").any { it.code == "6E" })
        assertEquals("6E", ManualFlightRules.airlines("6e").first().code)
        assertEquals("AI", ManualFlightRules.airlines("air india").first().code)
        assertTrue(ManualFlightRules.airlines("").size > 20)
        assertTrue(ManualFlightRules.airlines("zzz").isEmpty())
    }

    @Test fun aHandMadeFlightKeysLikeABookedOne() {
        val m = manual()
        assertEquals(m.toBooked().key, m.key)
        assertEquals("6E2345|2026-11-12", m.key)
        assertEquals("6E 2345", m.toBooked().code)
    }

    @Test fun mergeKeepsTheTextVersionAndFillsGaps() {
        val fromText = BookedFlight(indigo, "2345", today, Place("MAA", "Chennai"), null, LocalTime.of(14, 35), null, null, null, "12", FlightStatusKind.DELAYED, 45, LocalTime.of(15, 20), 5L)
        val m = manual(dep = "09:00", arr = "11:00", note = "ABC123").copy(destinationCode = "DEL", originCode = "BOM")
        val merged = ManualFlights.merge(listOf(fromText), listOf(m))
        assertEquals(1, merged.size)
        val f = merged.single()
        assertEquals(FlightStatusKind.DELAYED, f.status)
        assertEquals("MAA", f.origin?.code)
        assertEquals("DEL", f.destination?.code)
        assertEquals(LocalTime.of(14, 35), f.departure)
        assertEquals(LocalTime.of(11, 0), f.arrival)
        assertEquals("ABC123", f.pnr)
        assertEquals("12", f.gate)
    }

    @Test fun mergeAddsFlightsTheTextsDoNotKnow() {
        val other = manual(id = "m2", number = "100")
        assertEquals(2, ManualFlights.merge(listOf(manual().toBooked()), listOf(manual(), other)).size)
        assertEquals(1, ManualFlights.merge(emptyList(), listOf(manual(), manual(id = "again"))).size)
        // Another day is another flight.
        assertEquals(2, ManualFlights.merge(listOf(manual().toBooked()), listOf(manual(id = "x", day = today.plusDays(1)))).size)
    }

    @Test fun expiresADayAfterLanding() {
        val m = manual(dep = "14:35", arr = "16:40")
        assertFalse(ManualFlights.isExpired(m, at("16:40"), ist))
        assertFalse(ManualFlights.isExpired(m, at("16:39", today.plusDays(1)), ist))
        assertTrue(ManualFlights.isExpired(m, at("16:41", today.plusDays(1)), ist))
    }

    @Test fun expiryWithoutTimesUsesTheDepartureOrTheDay() {
        val onlyDeparture = manual(dep = "14:35")
        assertFalse(ManualFlights.isExpired(onlyDeparture, at("20:00", today.plusDays(1)), ist))
        assertTrue(ManualFlights.isExpired(onlyDeparture, at("21:00", today.plusDays(1)), ist))
        val none = manual()
        assertFalse(ManualFlights.isExpired(none, at("23:00"), ist))
        assertFalse(ManualFlights.isExpired(none, at("23:59", today.plusDays(1)), ist))
        assertTrue(ManualFlights.isExpired(none, at("00:30", today.plusDays(2)), ist))
    }

    @Test fun expiryOfAnOvernightFlightCountsFromTheNextMorningLanding() {
        val m = manual(dep = "23:30", arr = "02:15")
        assertFalse(ManualFlights.isExpired(m, at("02:00", today.plusDays(2)), ist))
        assertTrue(ManualFlights.isExpired(m, at("02:16", today.plusDays(2)), ist))
    }

    @Test fun aLookupFillsRouteAndTimesOnEachAirportsClock() {
        val status = LiveStatus(
            FlightStatusKind.SCHEDULED, scheduledDeparture = at("14:35"), scheduledArrival = at("16:40"),
            originCode = "del", destinationCode = "DXB"
        )
        val c = ManualFlights.confirmed(manual(), status, ist)
        assertTrue(c.confirmed)
        assertEquals("DEL", c.originCode)
        assertEquals("DXB", c.destinationCode)
        assertEquals(LocalTime.of(14, 35), c.departure)
        // 16:40 in India is 15:10 in Dubai.
        assertEquals(LocalTime.of(15, 10), c.arrival)
    }

    @Test fun aLookupThatKnowsLessKeepsWhatWasThere() {
        val c = ManualFlights.confirmed(manual(dep = "09:00").copy(originCode = "BOM"), LiveStatus(FlightStatusKind.SCHEDULED), ist)
        assertEquals("BOM", c.originCode)
        assertEquals(LocalTime.of(9, 0), c.departure)
    }

    @Test fun storeSavesEditsRemovesAndUndoes() {
        var saved: List<ManualFlight> = emptyList()
        val store = ManualFlightStore { saved = it }
        store.save(manual(id = "a"))
        store.save(manual(id = "b", number = "100"))
        assertEquals(listOf("a", "b").toSet(), store.flights.value.map { it.id }.toSet())
        // An edit under the same id replaces; the same flight under another id replaces too.
        store.save(manual(id = "a", note = "XYZ789"))
        assertEquals(2, store.flights.value.size)
        assertEquals("XYZ789", store.flights.value.first { it.id == "a" }.pnrNote)
        store.save(manual(id = "c"))
        assertEquals(listOf("b", "c").toSet(), store.flights.value.map { it.id }.toSet())
        val removed = store.remove("b")!!
        assertEquals(1, store.flights.value.size)
        assertEquals(1, saved.size)
        store.save(removed)
        assertEquals(2, saved.size)
        assertNull(store.remove("nope"))
    }

    @Test fun storePrunesExpiredOnes() {
        val store = ManualFlightStore()
        store.save(manual(id = "old", day = today.minusDays(3), dep = "10:00", arr = "12:00"))
        store.save(manual(id = "new", day = today, dep = "10:00", arr = "12:00"))
        assertEquals(1, store.prune(at("12:00"), ist))
        assertEquals(listOf("new"), store.flights.value.map { it.id })
        assertEquals(0, store.prune(at("12:00"), ist))
    }

    @Test fun planCodecKeepsWhatTrackingNeedsAndNotThePnr() {
        val f = BookedFlight(indigo, "2345", today, Place("DEL", "Delhi"), Place(null, "Chennai"), LocalTime.of(14, 35), LocalTime.of(16, 40),
            "ABC123", "1", null, FlightStatusKind.DELAYED, 45, LocalTime.of(15, 20), 5L)
        val text = FlightPlanCodec.encode(listOf(f, f.copy(number = "9")))
        assertFalse("ABC123" in text)
        val back = FlightPlanCodec.decode(text)
        assertEquals(2, back.size)
        assertEquals(f.copy(pnr = null, lastMessageAt = 0L), back[0])
        assertEquals(emptyList<BookedFlight>(), FlightPlanCodec.decode("garbage\nmore"))
        assertEquals(emptyList<BookedFlight>(), FlightPlanCodec.decode(""))
    }
}
