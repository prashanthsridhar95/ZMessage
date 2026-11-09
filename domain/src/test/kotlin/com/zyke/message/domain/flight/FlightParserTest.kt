package com.zyke.message.domain.flight

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal fun <T : Any> some(value: T?): T { org.junit.Assert.assertNotNull("expected a value", value); return value!! }

/** Masked sample texts in the wording airlines and booking sites use. */
class FlightParserTest {
    private val received = LocalDate.of(2026, 11, 5)

    private fun parse(body: String, on: LocalDate = received) = FlightParser.parse(body, on)

    @Test
    fun indigoBookingWithCitiesCodesDateTimeAndPnr() {
        val f = some(parse("Booking confirmed. Your IndiGo flight 6E 2345 from Chennai (MAA) to Delhi (DEL) on 12 Nov 2026 departs at 14:35 and arrives 17:20. PNR: ABC123. Web check-in opens 48 hrs before departure."))
        assertEquals("6E", f.airline.code)
        assertEquals("IndiGo", f.airline.name)
        assertEquals("2345", f.number)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
        assertEquals("MAA", f.origin?.code)
        assertEquals("Chennai", f.origin?.city)
        assertEquals("DEL", f.destination?.code)
        assertEquals(LocalTime.of(14, 35), f.departure)
        assertEquals(LocalTime.of(17, 20), f.arrival)
        assertEquals("ABC123", f.pnr)
        assertEquals(FlightEvent.BOOKED, f.event)
    }

    @Test
    fun airIndiaDashedNumberCodePairAndTerminal() {
        val f = some(parse("Dear Customer, your booking is confirmed. Flight AI-101 DEL-BOM on 12-Nov-2026. Dep 06:30 Arr 08:40. PNR XY12Z3. Terminal 3."))
        assertEquals("AI", f.airline.code)
        assertEquals("101", f.number)
        assertEquals(FlightEvent.BOOKED, f.event)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
        assertEquals("DEL", f.origin?.code)
        assertEquals("BOM", f.destination?.code)
        assertEquals("Mumbai", f.destination?.city)
        assertEquals(LocalTime.of(6, 30), f.departure)
        assertEquals(LocalTime.of(8, 40), f.arrival)
        assertEquals("XY12Z3", f.pnr)
        assertEquals("3", f.terminal)
    }

    @Test
    fun slashDateAndTwoDigitYear() {
        val f = some(parse("Vistara: Booking confirmed. Flight UK 832 BOM to DEL on 12/11/26, departure 09:55 hrs. PNR QW4ER5", received))
        assertEquals("UK", f.airline.code)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
        assertEquals(LocalTime.of(9, 55), f.departure)
    }

    @Test
    fun theBookingDayIsNotTheDayOfTravel() {
        val f = some(parse("SpiceJet: Your flight SG 123 DEL-GOI is booked on 05-Nov-2026 for travel on 20 Nov 2026 at 07:10. PNR: ZZ99QA"))
        assertEquals(LocalDate.of(2026, 11, 20), f.date)
        assertEquals(LocalTime.of(7, 10), f.departure)
    }

    @Test
    fun akasaTwelveHourClockAndCityNames() {
        val f = some(parse("Akasa Air: Your flight QP 1105 from Bengaluru to Mumbai is confirmed for 14 Nov. Departs 6:45 PM, arrives 8:30 PM. Booking ref AKQ7Z9", received))
        assertEquals("QP", f.airline.code)
        assertEquals("1105", f.number)
        assertEquals("BLR", f.origin?.code)
        assertEquals("BOM", f.destination?.code)
        assertEquals(LocalDate.of(2026, 11, 14), f.date)
        assertEquals(LocalTime.of(18, 45), f.departure)
        assertEquals(LocalTime.of(20, 30), f.arrival)
        assertEquals("AKQ7Z9", f.pnr)
    }

    @Test
    fun airIndiaExpressAndCompactNumber() {
        val f = some(parse("Air India Express: Flight IX1234 COK-DXB on 12Nov is confirmed. PNR: RT55YU. ETD 02:10"))
        assertEquals("IX", f.airline.code)
        assertEquals("1234", f.number)
        assertEquals("COK", f.origin?.code)
        assertEquals("DXB", f.destination?.code)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
        assertEquals(LocalTime.of(2, 10), f.departure)
    }

    @Test
    fun webCheckInOpen() {
        val f = some(parse("Web check-in is now open for your IndiGo flight 6E 2345 Chennai-Delhi on 12 Nov. Check-in at goindigo.in. PNR ABC123"))
        assertEquals(FlightEvent.CHECK_IN, f.event)
        assertEquals("ABC123", f.pnr)
    }

    @Test
    fun boardingWithGateAndTerminal() {
        val f = some(parse("Boarding for Air India flight AI 101 has started at Gate 12, Terminal 3. Please proceed to the gate."))
        assertEquals(FlightEvent.BOARDING, f.event)
        assertEquals("12", f.gate)
        assertEquals("3", f.terminal)
        assertNull(f.date)
        assertNull(f.departure)
    }

    @Test
    fun delayWithMinutesAndNewTime() {
        val f = some(parse("IndiGo: Flight 6E 2345 MAA-DEL on 12 Nov is delayed by 45 mins. New departure time 15:20. We regret the inconvenience."))
        assertEquals(FlightEvent.DELAYED, f.event)
        assertEquals(45, f.delayMinutes)
        assertEquals(LocalTime.of(15, 20), f.newDeparture)
        assertNull(f.departure)
    }

    @Test
    fun delayWithScheduledAndRevisedTimes() {
        val f = some(parse("Vistara regrets that flight UK 832 is delayed. Scheduled departure 09:55, revised departure 10:40. PNR QW4ER5"))
        assertEquals(FlightEvent.DELAYED, f.event)
        assertEquals(LocalTime.of(9, 55), f.departure)
        assertEquals(LocalTime.of(10, 40), f.newDeparture)
    }

    @Test
    fun delayInHoursAndMinutes() {
        val f = some(parse("SpiceJet: flight SG 123 is delayed by 1 hr 30 min. Now departing 08:40."))
        assertEquals(90, f.delayMinutes)
        assertEquals(LocalTime.of(8, 40), f.newDeparture)
    }

    @Test
    fun gateChange() {
        val f = some(parse("Gate change: flight UK 832 BOM-DEL now departs from Gate 24. Boarding at 09:15."))
        assertEquals(FlightEvent.GATE_CHANGE, f.event)
        assertEquals("24", f.gate)
        assertNull(f.departure)
    }

    @Test
    fun cancellation() {
        val f = some(parse("SpiceJet regrets to inform that flight SG 123 on 12/11/26 is cancelled. Please visit spicejet.com for a refund or rebooking. PNR: ZZ99QA"))
        assertEquals(FlightEvent.CANCELLED, f.event)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
    }

    @Test
    fun reminderToday() {
        val on = LocalDate.of(2026, 11, 12)
        val f = some(parse("Reminder: your Akasa Air flight QP 1105 BLR-BOM departs today at 18:45. Reach the airport 2 hrs early. PNR AKQ7Z9", on))
        assertEquals(FlightEvent.REMINDER, f.event)
        assertEquals(on, f.date)
        assertEquals(LocalTime.of(18, 45), f.departure)
    }

    @Test
    fun cancellationWordsInABookingAreNotACancellation() {
        val f = some(parse("Booking confirmed: IndiGo 6E 6021 DEL-BLR on 15 Nov 2026 at 21:00. Free cancellation up to 2 hrs before. PNR ABC123"))
        assertEquals(FlightEvent.BOOKED, f.event)
    }

    @Test
    fun lowerCaseMonthAndOrdinalDay() {
        val f = some(parse("Your flight AI 506 DEL to BLR on 12th November 2026 is confirmed. Dep 07:05. PNR: AB12CD"))
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
    }

    @Test
    fun notAFlight() {
        assertNull(parse("Your OTP is 123456. Do not share it."))
        assertNull(parse("Rs 500 debited from a/c XX1234 on 12-Nov-26. AI 101 refund pending."))
        assertNull(parse("IndiGo SALE! Fares from Rs 1,499 on 6E 2345 DEL-BOM. Book now, use code FLY."))
        assertNull(parse("Your PNR 1234567890 train 12345 is confirmed"))
    }

    @Test
    fun neverThrowsOnOddInput() {
        assertNull(parse(""))
        assertNull(parse("flight " + "9".repeat(5000)))
    }
}
