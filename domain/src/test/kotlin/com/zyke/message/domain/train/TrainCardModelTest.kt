package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import com.zyke.message.domain.flight.TimeShift
import com.zyke.message.domain.train.TrainFixtures.DOJ
import com.zyke.message.domain.train.TrainFixtures.ist
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainCardModelTest {
    private val train = BookedTrain(
        "12633", "Kanniyakumari Express", DOJ, Place("MS", "Chennai Egmore"), Place("TPJ", "Tiruchirappalli"), LocalTime.of(21, 30), LocalTime.of(4, 35),
        trainClass = "SL", coach = "S4", berth = "32 SU"
    )
    private fun live(json: String, at: Instant, t: BookedTrain = train) =
        (TrainStatusParser.parse(json, t, DOJ, at.toEpochMilli()) as TrainLookup.Found).live
    private fun model(t: BookedTrain, l: TrainLive?, now: Instant, liveOn: Boolean = true) = TrainCardModel.of(TrainTracker.snapshot(t, l, now), now, liveOn)
    private val nb = ' '

    @Test fun beforeTheJourneyFromTheMessageOnly() {
        val now = ist(12, 18, 10)
        val m = model(train, null, now, liveOn = false)
        assertEquals("Not started", m.pill)
        assertEquals("MS", m.origin.code)
        assertEquals("Chennai Egmore", m.origin.city)
        assertEquals("21:30", m.origin.time)
        assertNull(m.origin.was)
        assertEquals("04:35 +1", m.destination.time)
        assertEquals("Departs in 3h 20m", m.supporting.replace(nb, ' '))
        assertTrue('\u00A0' in m.supporting)
        assertNull(m.next)
        assertEquals(listOf("SL · S4 · 32 SU"), m.chips)
        assertEquals("From your message", m.caption)
        assertEquals("12633 · MS → TPJ · 21:30", m.summary)
        assertEquals("7h${nb}5m", m.duration)
        assertEquals(0f, m.progress, 0f)
        assertFalse(m.isFinal)
    }

    @Test fun runningLateAtYourStation() {
        val now = ist(12, 23, 0)
        val mine = train.copy(from = Place("VM", "Villupuram"), departure = LocalTime.of(23, 42))
        val base = live(TrainFixtures.running, now, mine)
        val stops = base.stops.map { s -> if (s.code == "VM") s.copy(actualArrival = null, actualDeparture = null) else s }
        val seen = base.copy(stops = stops, lastStationCode = "CGL", lastSeenAt = ist(12, 22, 37), delayMinutes = 12)
        val m = model(mine, seen, now)
        assertEquals("Late 12${nb}min", m.pill)
        assertEquals("Reaches your station in 52m", m.supporting.replace(nb, ' '))
        assertEquals("Last seen at Chengalpattu · 22:37, 12 min late", m.next)
        assertEquals("23:54", m.origin.time)
        assertEquals("23:42", m.origin.was)
        assertEquals(TimeShift.LATER, m.origin.shift)
        assertEquals("VM", m.origin.code)
    }

    @Test fun inTransitShowsTheNextStation() {
        val now = ist(13, 0, 15)
        val m = model(train, live(TrainFixtures.running, now), now)
        assertEquals("Late 12${nb}min", m.pill)
        assertEquals("Arrives in 4h${nb}32m · 12 min late", m.supporting.replace(nb, ' ').replace("4h 32m", "4h${nb}32m"))
        assertEquals("Next station: Vriddhachalam Jn · expected 00:57", m.next)
        assertEquals("04:47 +1", m.destination.time)
        assertEquals("04:35", m.destination.was)
        assertEquals(TimeShift.LATER, m.destination.shift)
        assertEquals(listOf("Platform 2", "SL · S4 · 32 SU"), m.chips)
        assertTrue(m.progress in 0.3f..0.5f)
        assertEquals("Updated just now", m.caption)
        assertTrue(m.showTrain)
    }

    @Test fun arrivedSaysHowLate() {
        val now = ist(13, 4, 50)
        val m = model(train, live(TrainFixtures.arrived, now), now)
        assertEquals("Arrived", m.pill)
        assertEquals("Arrived 04:41 · 6 min late", m.supporting.replace(nb, ' '))
        assertEquals(1f, m.progress, 0f)
        assertNull(m.next)
        assertTrue(m.isFinal)
        assertEquals(listOf("Platform 2", "SL · S4 · 32 SU"), m.chips)
    }

    @Test fun cancelledHasNoChipsOrTrain() {
        val now = ist(12, 20, 0)
        val m = model(train, live(TrainFixtures.cancelled, now), now)
        assertEquals("Cancelled", m.pill)
        assertEquals("Cancelled · check with Indian Railways", m.supporting)
        assertTrue(m.chips.isEmpty())
        assertFalse(m.showTrain)
        assertEquals(0f, m.progress, 0f)
    }

    @Test fun neverAnEmptyChip() {
        val bare = train.copy(trainClass = null, coach = null, berth = null, ticket = null)
        assertTrue(model(bare, null, ist(12, 12, 0), false).chips.isEmpty())
        val waiting = train.copy(coach = null, berth = null, ticket = "WL 8")
        assertEquals(listOf("SL · WL 8"), model(waiting, null, ist(12, 12, 0), false).chips)
        val cnf = train.copy(coach = null, berth = null, ticket = "CNF")
        assertEquals(listOf("SL"), model(cnf, null, ist(12, 12, 0), false).chips)
    }

    @Test fun theSpokenSentenceHasEverythingWithoutBreakingSpaces() {
        val now = ist(13, 0, 15)
        val m = model(train, live(TrainFixtures.running, now), now)
        assertFalse(nb in m.spoken)
        assertTrue("Train Kanniyakumari Express 12633, Late 12 min" in m.spoken)
        assertTrue("Chennai Egmore to Tiruchirappalli" in m.spoken)
        assertTrue("Platform 2" in m.spoken)
    }

    @Test fun featuredFirstPutsFinishedOnesLast() {
        val done = model(train, live(TrainFixtures.arrived, ist(13, 4, 50)), ist(13, 4, 50))
        val todo = model(train.copy(number = "12000"), null, ist(12, 18, 0))
        assertEquals(listOf("12000", "12633"), TrainCardModel.featuredFirst(listOf(done, todo)).map { it.number })
    }

    @Test fun aWasDueTrainStillSaysSo() {
        val m = model(train, null, ist(12, 21, 50), false)
        assertEquals("Was due 21:30 · 20m ago", m.supporting.replace(nb, ' '))
    }
}
