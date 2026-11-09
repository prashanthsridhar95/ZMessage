package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import com.zyke.message.domain.train.TrainFixtures.DOJ
import com.zyke.message.domain.train.TrainFixtures.ist
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainNotificationTextTest {
    private val phone = ZoneId.of("Asia/Kolkata")
    private val train = BookedTrain("12633", "Kanniyakumari Express", DOJ, Place("MS", "Chennai Egmore"), Place("TPJ", "Tiruchirappalli"), LocalTime.of(21, 30), LocalTime.of(4, 35))
    private fun live(json: String, at: Instant, t: BookedTrain = train) =
        (IndianRailApiParser.parse(json, t, DOJ, at.toEpochMilli()) as TrainLookup.Found).live
    private fun snap(t: BookedTrain, l: TrainLive?, now: Instant) = TrainTracker.snapshot(t, l, now)

    @Test fun inTransitLateWithTheNextStation() {
        val now = ist(13, 0, 15)
        val n = TrainNotificationText.inTransit(snap(train, live(TrainFixtures.running, now), now), now, phone, now.toEpochMilli())
        assertEquals("Arrives Tiruchirappalli 04:47 +1 (12 min late)", n.title)
        assertEquals("Running 12 min late · next: Vriddhachalam Jn 00:57", n.text)
        assertEquals("12633 · Updated 00:15:00", n.subText)
        assertEquals("4h 32m", n.chip)
        assertEquals(ist(13, 4, 47), n.countdownTo)
        assertEquals(listOf(0, 100), n.points)
        assertTrue(n.progress!! in 1..99)
    }

    @Test fun inTransitOnTimeAndEarly() {
        val now = ist(12, 22, 30)
        val tbm = live(TrainFixtures.running, now).let { l -> l.copy(delayMinutes = 0, stops = l.stops.map { it.copy(expectedArrival = it.scheduledArrival, expectedDeparture = it.scheduledDeparture, actualArrival = it.scheduledArrival?.takeIf { _ -> false }) }) }
        val s = snap(train, tbm, now)
        val n = TrainNotificationText.inTransit(s, now, phone, null)
        assertTrue(n.title, n.title.startsWith("Arrives Tiruchirappalli 04:35"))
        assertTrue(n.title, n.title.endsWith("(on time)"))
        assertTrue(n.text.startsWith("On time"))
        assertEquals("12633 · From your booking", n.subText)
        val early = TrainNotificationText.inTransit(s.copy(delayMinutes = -7, bestArrival = ist(13, 4, 28)), now, phone, null)
        assertTrue(early.title, early.title.endsWith("(7 min early)"))
        assertTrue(early.text.startsWith("Running 7 min early"))
    }

    @Test fun atAStationSaysWhere() {
        val now = ist(12, 23, 41)
        val vm = TrainFixtures.stop(1, "VM", "Villupuram", "23:40", "23:42", "23:43", "23:45", "3 M", "3 M")
        val json = """{"ResponseCode":"200","StartDate":"12-11-2026","TrainNumber":"12633","CurrentStation":$vm,"TrainRoute":[${TrainFixtures.stop(0, "MS", "Chennai Egmore", null, "21:30", ad = "21:33", delayDep = "3 M")},$vm,${TrainFixtures.stop(2, "TPJ", "Tiruchirappalli", "04:35", null, "04:38", null, "3 M")}],"Message":"SUCCESS"}"""
        val n = TrainNotificationText.inTransit(snap(train, live(json, now), now), now, phone, now.toEpochMilli())
        assertEquals("On time · at Villupuram", n.text)
    }

    @Test fun overnightAndAfterMidnightArrivalsCarryThePlusDay() {
        val s = snap(train, null, ist(12, 22, 0))
        assertEquals("04:35 +1", TrainText.arrivalClock(s))
        val sameDay = snap(train.copy(departure = LocalTime.of(6, 0), arrival = LocalTime.of(14, 0)), null, ist(12, 7, 0))
        assertEquals("14:00", TrainText.arrivalClock(sameDay))
        val multi = snap(train.copy(departure = LocalTime.of(5, 0), arrival = LocalTime.of(9, 10), arrivalDayOffset = 2), null, ist(12, 7, 0))
        assertEquals("09:10 +2", TrainText.arrivalClock(multi))
    }

    @Test fun refreshFailedSaysSo() {
        val now = ist(13, 0, 15)
        val n = TrainNotificationText.inTransit(snap(train, live(TrainFixtures.running, now), now), now, phone, now.toEpochMilli() - 600_000, refreshFailed = true)
        assertEquals("Can't refresh · last update 00:05", n.text)
        assertTrue(n.subText!!.startsWith("Running 12 min late"))
    }

    @Test fun approachingBeforeTheTrainLeavesYourStation() {
        val now = ist(12, 21, 0)
        val n = TrainNotificationText.approaching(snap(train, null, now), now, phone, null)
        assertEquals("Departs in 30 min", n.title)
        assertEquals("MS → TPJ · 21:30", n.text)
        assertEquals("30m", n.chip)
        val late = train.copy(status = TrainStatusKind.RUNNING_LATE, delayMinutes = 25, newDeparture = LocalTime.of(21, 55))
        assertEquals("Departs in 55 min (25 min late)", TrainNotificationText.approaching(snap(late, null, now), now, phone, null).title)
    }

    @Test fun approachingMidRouteSaysWhenItReachesYou() {
        val mine = train.copy(from = Place("VM", "Villupuram"), departure = LocalTime.of(23, 42))
        val now = ist(12, 22, 50)
        val base = live(TrainFixtures.running, now, mine)
        val seen = base.copy(stops = base.stops.map { if (it.code == "VM") it.copy(actualArrival = null, actualDeparture = null) else it }, lastStationCode = "TBM", atStation = false, delayMinutes = 12)
        val n = TrainNotificationText.approaching(snap(mine, seen, now), now, phone, now.toEpochMilli())
        assertEquals("Reaches VM in 1 h 2 min (12 min late)", n.title)
    }

    @Test fun arrivedLateOnTimeEarly() {
        val now = ist(13, 4, 50)
        val s = snap(train, live(TrainFixtures.arrived, now), now)
        val n = TrainNotificationText.arrived(s)
        assertEquals("Arrived at TPJ at 04:41", n.title)
        assertEquals("6 min late (scheduled 04:35)", n.text)
        assertEquals("Arrived around 04:41".replace("Arrived", "Arrived at TPJ"), TrainNotificationText.arrived(s, estimated = true).title)
        val onTime = TrainNotificationText.arrived(s.copy(bestArrival = ist(13, 4, 36), platform = null))
        assertEquals("On time", onTime.text)
        val early = TrainNotificationText.arrived(s.copy(bestArrival = ist(13, 4, 28)))
        assertEquals("7 min early (scheduled 04:35)", early.text)
        assertEquals("Arrived", TrainNotificationText.arrived(s.copy(bestArrival = null, scheduledArrival = null, platform = null)).text)
    }

    @Test fun cancelledPartlyCancelledDivertedAndUnconfirmed() {
        val s = snap(train, null, ist(12, 20, 0))
        assertEquals("Cancelled · MS → TPJ", TrainNotificationText.cancelled(s).title)
        assertEquals("Partly cancelled · MS → TPJ", TrainNotificationText.partiallyCancelled(s).title)
        assertEquals("Diverted · MS → TPJ", TrainNotificationText.diverted(s).title)
        assertEquals("Arrival not confirmed; was due 04:35", TrainNotificationText.unconfirmed(s).text)
        assertEquals("Tracking paused", TrainNotificationText.paused().title)
    }

    @Test fun promptsAndTheLockScreenVersion() {
        val now = ist(12, 21, 20)
        val s = snap(train, null, now)
        assertEquals("MS → TPJ", TrainNotificationText.tapToStart(s, now).title)
        assertEquals("Departs 21:30 · in 10m · tap to start live tracking", TrainNotificationText.tapToStart(s, now).text)
        assertEquals("Departs 21:30 · in 10m · arrives 04:35 +1 (from your booking)", TrainNotificationText.reminder(s, now).text)
        assertEquals("Train journey in progress", TrainNotificationText.PUBLIC_TITLE)
        assertEquals("Your train 12633", TrainNotificationText.route(s.copy(from = null, to = null)))
        assertNull(TrainNotificationText.cancelled(s).chip)
        assertNotNull(TrainNotificationText.cancelled(s).text)
    }
}
