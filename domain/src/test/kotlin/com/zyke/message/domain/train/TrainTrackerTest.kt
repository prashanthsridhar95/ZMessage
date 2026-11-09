package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import com.zyke.message.domain.flight.some
import com.zyke.message.domain.train.TrainFixtures.DOJ
import com.zyke.message.domain.train.TrainFixtures.ist
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainTrackerTest {
    private val train = BookedTrain(
        "12633", "Kanniyakumari Express", DOJ, Place("MS", "Chennai Egmore"), Place("TPJ", "Tiruchirappalli"), LocalTime.of(21, 30), LocalTime.of(4, 35),
        trainClass = "SL", coach = "S4", berth = "32 SU", pnr = "1234567890"
    )
    private fun sms(body: String, at: Instant) = TrainSms(body, at.toEpochMilli())
    private fun live(json: String, at: Instant, t: BookedTrain = train) =
        (TrainStatusParser.parse(json, t, DOJ, at.toEpochMilli()) as TrainLookup.Found).live

    @Test fun textsAboutOneJourneyAreMergedAndALaterOneWins() {
        val now = ist(12, 12, 0)
        val trains = TrainTracker.bookings(
            listOf(
                sms(TrainFixtures.IRCTC_BOOKING, ist(5, 10, 0)), sms(TrainFixtures.CHART, ist(12, 9, 0)),
                sms(TrainFixtures.PLATFORM, ist(12, 20, 0)), sms(TrainFixtures.DELAY, ist(12, 20, 30))
            ), now
        )
        val t = trains.single()
        assertEquals("12633|2026-11-12", t.key)
        assertEquals("S4", t.coach)
        assertEquals("33 LB", t.berth)
        assertEquals("CNF", t.ticket)
        assertEquals("4", t.platform)
        assertTrue(t.chartPrepared)
        assertEquals(TrainStatusKind.RUNNING_LATE, t.status)
        assertEquals(45, t.delayMinutes)
        assertEquals(LocalTime.of(22, 15), t.newDeparture)
        assertEquals(LocalTime.of(21, 30), t.departure)
        assertEquals("1234567890", t.pnr)
        assertEquals(listOf("SL", "S4 · 33 LB"), t.chips)
    }

    @Test fun aTextWithNoDateJoinsTheNearestJourneyOfThatTrain() {
        val trains = TrainTracker.bookings(listOf(sms(TrainFixtures.IRCTC_BOOKING, ist(5, 10, 0)), sms(TrainFixtures.PLATFORM, ist(12, 20, 0))), ist(12, 21, 0))
        assertEquals("4", trains.single().platform)
    }

    @Test fun aCancelledTicketNeverShowsAndARebookingDoes() {
        val gone = "Your ticket PNR 1234567890 for train 12633 on 12-11-2026 is cancelled. Refund Rs.380."
        val cancelled = TrainTracker.bookings(listOf(sms(TrainFixtures.IRCTC_BOOKING, ist(5, 10, 0)), sms(gone, ist(6, 10, 0))), ist(12, 12, 0))
        assertTrue(cancelled.single().ticketCancelled)
        assertFalse(TrainTracker.isToday(cancelled.single(), ist(12, 12, 0)))
        val again = TrainTracker.bookings(listOf(sms(TrainFixtures.IRCTC_BOOKING, ist(5, 10, 0)), sms(gone, ist(6, 10, 0)), sms(TrainFixtures.IRCTC_BOOKING, ist(7, 10, 0))), ist(12, 12, 0))
        assertFalse(again.single().ticketCancelled)
    }

    @Test fun aReschedulingReplacesTheTimeAndIsNotADelay() {
        val t = TrainTracker.bookings(listOf(sms(TrainFixtures.IRCTC_BOOKING, ist(5, 10, 0)), sms(TrainFixtures.RESCHEDULED, ist(10, 10, 0))), ist(11, 0, 0)).single()
        assertEquals(LocalTime.of(23, 10), t.departure)
        assertEquals(TrainStatusKind.SCHEDULED, t.status)
        assertNull(t.delayMinutes)
    }

    @Test fun theArrivalIsTheNextDayWhenTheTimeIsEarlier_andAMultiDayJourneyUsesItsOffset() {
        assertEquals(ist(13, 4, 35), TrainTracker.scheduledArrival(train))
        val long = train.copy(departure = LocalTime.of(5, 0), arrival = LocalTime.of(9, 10), arrivalDayOffset = 2)
        assertEquals(ist(14, 9, 10), TrainTracker.scheduledArrival(long))
        // Same-day journey.
        assertEquals(ist(12, 14, 0), TrainTracker.scheduledArrival(train.copy(departure = LocalTime.of(6, 0), arrival = LocalTime.of(14, 0))))
    }

    @Test fun todayRunsFromTheStartOfTheDayUntilAfterArrival() {
        assertFalse(TrainTracker.isToday(train, ist(11, 23, 59)))
        assertTrue(TrainTracker.isToday(train, ist(12, 0, 0)))
        assertTrue(TrainTracker.isToday(train, ist(13, 2, 0)))
        assertTrue(TrainTracker.isToday(train, ist(13, 7, 30)))
        assertFalse(TrainTracker.isToday(train, ist(13, 7, 36)))
    }

    @Test fun aSmallHoursDepartureShowsFromTheEveningBefore() {
        val early = train.copy(departure = LocalTime.of(0, 30), arrival = LocalTime.of(8, 0))
        assertFalse(TrainTracker.isToday(early, ist(11, 18, 29)))
        assertTrue(TrainTracker.isToday(early, ist(11, 18, 31)))
    }

    @Test fun aJourneyWithNoTimesStaysForItsDay_andACancelledOneToo() {
        val bare = train.copy(departure = null, arrival = null)
        assertTrue(TrainTracker.isToday(bare, ist(12, 23, 0)))
        assertFalse(TrainTracker.isToday(bare, ist(13, 0, 1)))
        val cancelled = train.copy(status = TrainStatusKind.CANCELLED)
        assertTrue(TrainTracker.isToday(cancelled, ist(12, 23, 0)))
        assertFalse(TrainTracker.isToday(cancelled, ist(13, 0, 1)))
    }

    @Test fun withoutLiveDataTheSnapshotIsTheMessageSchedule() {
        val s = TrainTracker.snapshot(train, null, ist(12, 18, 0))
        assertEquals(TrainStatusKind.SCHEDULED, s.kind)
        assertEquals(ist(12, 21, 30), s.bestDeparture)
        assertEquals(ist(13, 4, 35), s.bestArrival)
        assertFalse(s.isLive)
        assertEquals("04:35 +1", TrainText.arrivalClock(s))
        val late = train.copy(status = TrainStatusKind.RUNNING_LATE, delayMinutes = 45, newDeparture = LocalTime.of(22, 15))
        val l = TrainTracker.snapshot(late, null, ist(12, 18, 0))
        assertEquals(TrainStatusKind.RUNNING_LATE, l.kind)
        assertEquals(45, l.delayMinutes)
        assertEquals(ist(12, 22, 15), l.bestDeparture)
        assertEquals(ist(13, 5, 20), l.bestArrival)
    }

    @Test fun aRunningTrainSeenFromItsFirstStation() {
        val now = ist(12, 23, 55)
        val s = TrainTracker.snapshot(train, live(TrainFixtures.running, now), now)
        assertEquals(TrainStatusKind.RUNNING_LATE, s.kind)
        assertTrue(s.departed)
        assertFalse(s.arrived)
        assertEquals(12, s.delayMinutes)
        assertEquals(ist(12, 21, 42), s.bestDeparture)
        assertEquals(ist(13, 4, 47), s.bestArrival)
        assertEquals(listOf("VRI", "ALU", "TPJ"), s.upcoming.map { it.code })
        assertEquals("VM", s.position?.code)
        assertEquals("2", s.platform)
        assertNull(s.reachesBoarding)
        assertTrue(s.isLive)
    }

    @Test fun boardingMidRouteShowsTheDelayAtYourStation() {
        val mine = train.copy(from = Place("VM", "Villupuram"), departure = LocalTime.of(23, 42), arrival = LocalTime.of(4, 35))
        val json = TrainFixtures.running
        val now = ist(12, 22, 50)
        // The train is at Tambaram on its way: only the first stops have happened.
        val base = live(json, now, mine)
        val stops = base.stops.map { s -> if (s.code in setOf("VM")) s.copy(actualArrival = null, actualDeparture = null) else s }
        val seen = base.copy(stops = stops, lastStationCode = "TBM", lastSeenAt = ist(12, 22, 14), delayMinutes = 12)
        val s = TrainTracker.snapshot(mine, seen, now)
        assertFalse(s.departed)
        assertEquals(TrainStatusKind.RUNNING_LATE, s.kind)
        assertEquals(12, s.delayMinutes)
        assertEquals(ist(12, 23, 54), s.bestDeparture)
        assertEquals(ist(12, 23, 52), s.reachesBoarding)
        assertEquals("TBM", s.position?.code)
        assertEquals("CGL", s.nextStop?.code)
    }

    @Test fun arrivedAndCancelledAndNotStarted() {
        val at = ist(13, 4, 50)
        val a = TrainTracker.snapshot(train, live(TrainFixtures.arrived, at), at)
        assertEquals(TrainStatusKind.ARRIVED, a.kind)
        assertTrue(a.arrived)
        assertEquals(ist(13, 4, 41), a.bestArrival)
        assertEquals(6, a.delayMinutes)
        assertEquals("2", a.platform)
        assertTrue(a.upcoming.isEmpty())
        val c = TrainTracker.snapshot(train, live(TrainFixtures.cancelled, at), at)
        assertEquals(TrainStatusKind.CANCELLED, c.kind)
        val n = TrainTracker.snapshot(train, live(TrainFixtures.notStarted, ist(12, 18, 0)), ist(12, 18, 0))
        assertEquals(TrainStatusKind.SCHEDULED, n.kind)
        assertFalse(n.departed)
    }

    @Test fun todaySortsByTheBestDeparture() {
        val late = train.copy(number = "12000", status = TrainStatusKind.RUNNING_LATE, delayMinutes = 300)
        val other = train.copy(number = "12001", departure = LocalTime.of(23, 0))
        val sorted = TrainTracker.today(listOf(late, other, train), ist(12, 12, 0)).map { it.number }
        assertEquals(listOf("12633", "12001", "12000"), sorted)
    }

    @Test fun delayWordsAndAgainstSchedule() {
        assertEquals("on time", TrainText.againstMinutes(4))
        assertEquals("on time", TrainText.againstMinutes(-4))
        assertEquals("12 min late", TrainText.againstMinutes(12))
        assertEquals("1 h late", TrainText.againstMinutes(60))
        assertEquals("1 h 5 min late", TrainText.againstMinutes(65))
        assertEquals("6 min early", TrainText.againstMinutes(-6))
        assertEquals("2 h 10 min early", TrainText.againstMinutes(-130))
        assertNull(TrainText.againstSchedule(null, ist(12, 1, 0)))
    }

    @Test fun lastSeenSaysWhereWhenAndHowLate() {
        val now = ist(13, 0, 15)
        val s = TrainTracker.snapshot(train, live(TrainFixtures.running, now), now)
        assertEquals("Last seen at Villupuram Jn at 23:54, running 12 min late", TrainText.lastSeen(s))
        assertEquals("Last seen at Villupuram Jn at 23:54, on time", TrainText.lastSeen(s.copy(position = s.position?.copy(delayMinutes = 2))))
        assertEquals("Last seen at Villupuram Jn at 23:54, running 1 h 5 min early", TrainText.lastSeen(s.copy(position = s.position?.copy(delayMinutes = -65))))
        assertNull(TrainText.lastSeen(s.copy(position = null)))
    }

    @Test fun progressRunsFromDepartureToArrival() {
        val now = ist(13, 0, 15)
        val s = TrainTracker.snapshot(train, live(TrainFixtures.running, now), now)
        val p = TrainText.progress(s, now)
        assertTrue("progress $p", p in 0.3f..0.45f)
        assertEquals(0f, TrainText.progress(TrainTracker.snapshot(train, null, ist(12, 18, 0)), ist(12, 18, 0)), 0f)
    }
}
