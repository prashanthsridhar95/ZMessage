package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import com.zyke.message.domain.flight.some
import com.zyke.message.domain.train.TrainFixtures.DOJ
import com.zyke.message.domain.train.TrainFixtures.ist
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against hand-built replies (see [TrainFixtures]): this proves the parser reads the shape it was written for, not that the live service sends it. */
class TrainStatusParserTest {
    private val train = BookedTrain("12633", "Kanniyakumari Express", DOJ, Place("MS", "Chennai Egmore"), Place("TPJ", "Tiruchirappalli"), LocalTime.of(21, 30), LocalTime.of(4, 35))
    private val fetched = ist(12, 23, 55).toEpochMilli()

    private fun found(json: String, t: BookedTrain = train) = (TrainStatusParser.parse(json, t, DOJ, fetched) as TrainLookup.Found).live

    @Test fun aRunningTrainWithItsRoute() {
        val live = found(TrainFixtures.running)
        assertEquals("KANYAKUMARI EXP", live.trainName)
        assertEquals(7, live.stops.size)
        assertEquals("VM", live.lastStationCode)
        assertEquals(12, live.delayMinutes)
        assertTrue(live.started)
        assertFalse(live.atStation)
        assertFalse(live.cancelled)
        assertEquals(fetched, live.updatedAt)
        val ms = live.stops[0]
        assertEquals(ist(12, 21, 30), ms.scheduledDeparture)
        assertEquals(ist(12, 21, 42), ms.actualDeparture)
        assertEquals("4", ms.platform)
        assertNull(ms.scheduledArrival)
    }

    @Test fun stopsAfterMidnightFallOnTheNextDay() {
        val live = found(TrainFixtures.running)
        val vri = live.stops[4]
        assertEquals(2, vri.day)
        assertEquals(ist(13, 0, 45), vri.scheduledArrival)
        assertEquals(ist(13, 0, 57), vri.expectedArrival)
        assertEquals(ist(13, 4, 35), live.stops.last().scheduledArrival)
        assertEquals("2", live.stops.last().platform)
    }

    @Test fun notStartedAndCancelled() {
        val n = found(TrainFixtures.notStarted)
        assertFalse(n.started)
        assertNull(n.lastStationCode)
        val c = found(TrainFixtures.cancelled)
        assertTrue(c.cancelled)
        assertFalse(c.started)
    }

    @Test fun arrivedAtTheDestination() {
        val live = found(TrainFixtures.arrived)
        assertTrue(live.terminated)
        assertEquals("TPJ", live.lastStationCode)
        assertEquals(ist(13, 4, 41), live.stops.last().actualArrival)
        assertEquals(6, live.delayMinutes)
    }

    @Test fun theBoardingDayIsLearnedFromTheRoute() {
        assertEquals(1, found(TrainFixtures.running).boardingDay)
        assertEquals(2, found(TrainFixtures.running, train.copy(from = Place("VRI", "Vriddhachalam"))).boardingDay)
    }

    @Test fun aDataWrapperIsFine() {
        val wrapped = """{"data":${TrainFixtures.running}}"""
        assertEquals(7, found(wrapped).stops.size)
    }

    @Test fun noRunningInformationIsNotFound() {
        assertEquals(TrainLookup.NotFound, TrainStatusParser.parse(TrainFixtures.noRoute, train, DOJ, fetched))
    }

    @Test fun anotherTrainOrAnythingElseIsUnreadable() {
        assertEquals(TrainLookup.Unreadable, TrainStatusParser.parse(TrainFixtures.otherTrain, train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, TrainStatusParser.parse("<html>blocked</html>", train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, TrainStatusParser.parse("{}", train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, TrainStatusParser.parse("[1,2]", train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, TrainStatusParser.parse("""{"route":[1,2,3],"status":""}""", train, DOJ, fetched))
        assertEquals("12633", TrainStatusParser.query(train))
    }

    @Test fun anAtStationReply() {
        val json = """{"success":true,"train_number":"12633","status":"Arrived at Villupuram","current_station_code":"VM","delay":3,
            "route":[{"station_code":"MS","sch_dep":"21:30","has_arrived":true,"has_departed":true},{"station_code":"VM","sch_arr":"23:40","sch_dep":"23:42","has_arrived":true,"has_departed":false},{"station_code":"TPJ","sch_arr":"04:35"}]}"""
        val live = found(json)
        assertTrue(live.atStation)
        assertEquals("Villupuram", live.lastStationName)
    }
}
