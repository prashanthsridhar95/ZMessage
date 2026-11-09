package com.zyke.message.domain.train

import com.zyke.message.domain.flight.some
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainParserTest {
    private val received = LocalDate.of(2026, 11, 5)
    private fun parse(body: String) = TrainParser.parse(body, received)

    @Test fun irctcLabelledBooking() {
        val f = some(parse(TrainFixtures.IRCTC_BOOKING))
        assertEquals("12633", f.number)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
        assertEquals(LocalTime.of(21, 30), f.departure)
        assertEquals("SL", f.trainClass)
        assertEquals("MS", f.from?.code)
        assertEquals("Chennai Egmore", f.from?.city)
        assertEquals("TPJ", f.to?.code)
        assertEquals("1234567890", f.pnr)
        assertEquals("S4", f.coach)
        assertEquals("32 SU", f.berth)
        assertEquals("CNF", f.ticket)
        assertEquals(TrainEvent.BOOKED, f.event)
    }

    @Test fun bookingWithNamesTimesAndSlashBerth() {
        val f = some(parse(TrainFixtures.WORDS_BOOKING))
        assertEquals("12633", f.number)
        assertEquals("Kanyakumari Express", f.name)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
        assertEquals("MS", f.from?.code)
        assertEquals("TPJ", f.to?.code)
        assertEquals(LocalTime.of(21, 30), f.departure)
        assertEquals(LocalTime.of(4, 35), f.arrival)
        assertEquals("S4", f.coach)
        assertEquals("32 SU", f.berth)
        assertEquals(TrainEvent.BOOKED, f.event)
    }

    @Test fun chartPreparedGivesTheFinalBerth() {
        val f = some(parse(TrainFixtures.CHART))
        assertEquals(TrainEvent.CHART, f.event)
        assertEquals("S4", f.coach)
        assertEquals("33 LB", f.berth)
        assertEquals("CNF", f.ticket)
        assertEquals("MS", f.from?.code)
    }

    @Test fun platformText() {
        val f = some(parse(TrainFixtures.PLATFORM))
        assertEquals(TrainEvent.PLATFORM, f.event)
        assertEquals("4", f.platform)
        assertEquals("MS", f.from?.code)
        assertNull(f.date)
    }

    @Test fun delayWithMinutesAndNewDeparture() {
        val f = some(parse(TrainFixtures.DELAY))
        assertEquals(TrainEvent.DELAYED, f.event)
        assertEquals(45, f.delayMinutes)
        assertEquals(LocalTime.of(22, 15), f.newDeparture)
        assertEquals(LocalDate.of(2026, 11, 12), f.date)
        assertNull(f.departure)
    }

    @Test fun rescheduleKeepsTheNewTime() {
        val f = some(parse(TrainFixtures.RESCHEDULED))
        assertEquals(TrainEvent.RESCHEDULED, f.event)
        assertEquals(LocalTime.of(23, 10), f.newDeparture)
    }

    @Test fun trainCancelledIsNotTheTicketCancelled() {
        assertEquals(TrainEvent.CANCELLED, some(parse(TrainFixtures.TRAIN_CANCELLED)).event)
        // No train number in a ticket-cancellation text: left alone.
        assertNull(parse(TrainFixtures.TICKET_CANCELLED))
        val withTrain = some(parse("Your ticket PNR 1234567890 for train 12633 on 12-11-2026 is cancelled. Refund Rs.380."))
        assertEquals(TrainEvent.TICKET_CANCELLED, withTrain.event)
    }

    @Test fun partialCancellation() {
        val f = some(parse("Train 12633 of 12-11-2026 is partially cancelled between VM and TPJ. It will terminate at VM."))
        assertEquals(TrainEvent.PARTIALLY_CANCELLED, f.event)
    }

    @Test fun waitingListTakesTheNewestStatus() {
        val f = some(parse(TrainFixtures.WAITLIST))
        assertEquals("WL 8", f.ticket)
        assertEquals("MS", f.from?.code)
        assertEquals("TPJ", f.to?.code)
        assertNull(f.coach)
    }

    @Test fun racStatus() {
        assertEquals("RAC 4", some(parse("PNR 1234567890 Train 12633 DOJ 12-11-2026 status RAC/4 SL")).ticket)
    }

    @Test fun theBookingDayIsNotTheDayOfTravel() {
        val f = some(parse("Booked on 05-11-2026. PNR 1234567890, Train 12633, DOJ 20-11-2026, Dep 07:10, 3A, NDLS-MAS."))
        assertEquals(LocalDate.of(2026, 11, 20), f.date)
        assertEquals("3A", f.trainClass)
        assertEquals("NDLS", f.from?.code)
        assertEquals("MAS", f.to?.code)
    }

    @Test fun twoDigitYearAndTodayWords() {
        assertEquals(LocalDate.of(2026, 11, 12), some(parse("PNR 1234567890 Train 12633 DOJ 12/11/26 Dep 21:30")).date)
        assertEquals(received, some(parse("Reminder: your train 12633 leaves today at 21:30. PNR 1234567890")).date)
    }

    @Test fun ordinaryTextsAreLeftAlone() {
        assertNull(parse("Your OTP is 12633 for login. Do not share."))
        assertNull(parse("Flight 6E 2345 to Delhi. PNR ABC123"))
        assertNull(parse("Train your mind: 50% off sale on courses. Use code TRAIN50"))
        assertNull(parse("Rs 12633 debited from your account for train ticket"))
        assertNull(parse(""))
    }

    @Test fun neverThrowsOnLongOrOddText() {
        parse("train " + "12633 ".repeat(1000))
        parse("PNR: , TRAIN: 12633, FROM: TO: ,,,, ()")
    }
}
