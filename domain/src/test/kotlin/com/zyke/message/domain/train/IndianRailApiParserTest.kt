package com.zyke.message.domain.train

import com.zyke.message.domain.diag.LiveFailure
import com.zyke.message.domain.flight.Place
import com.zyke.message.domain.train.TrainFixtures.DOJ
import com.zyke.message.domain.train.TrainFixtures.ist
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Against the provider's documented example (kept verbatim in [TrainFixtures.documentationSample], sample from the provider's
 * documentation, 2018) and hand-built replies in its shape. This proves the parser reads the documented shape, not that the live
 * service still sends it.
 */
class IndianRailApiParserTest {
    private val train = BookedTrain("12633", "Kanniyakumari Express", DOJ, Place("MS", "Chennai Egmore"), Place("TPJ", "Tiruchirappalli"), LocalTime.of(21, 30), LocalTime.of(4, 35))
    private val fetched = ist(12, 23, 55).toEpochMilli()

    private fun found(json: String, t: BookedTrain = train, at: Long = fetched, start: LocalDate = DOJ) =
        (IndianRailApiParser.parse(json, t, start, at) as TrainLookup.Found).live

    // ---- the documentation's own example

    private val sampleStart = LocalDate.of(2018, 9, 15)
    private val sampleTrain = BookedTrain("12565", null, sampleStart, Place("SPJ", "Samastipur"), Place("NDLS", "New Delhi"), null, null)
    private fun at2018(day: Int, hour: Int, minute: Int): Instant = LocalDateTime.of(2018, 9, day, hour, minute).atZone(Stations.ZONE).toInstant()

    @Test fun theDocumentationSampleIsRead() {
        val live = found(TrainFixtures.documentationSample, sampleTrain, at2018(15, 16, 50).toEpochMilli(), sampleStart)
        // Jagatbela is the current station but is not in the route list: it is placed between Gorakhpur and Lucknow by its schedule.
        assertEquals(12, live.stops.size)
        assertEquals(listOf("DBG", "SPJ", "MFP", "HJP", "SEE", "CPR", "SV", "GKP", "JTB", "LKO", "CNB", "NDLS"), live.stops.map { it.code })
        assertEquals("JTB", live.lastStationCode)
        assertEquals("Jagatbela", live.lastStationName)
        assertEquals(14, live.delayMinutes)
        assertTrue(live.started)
        assertFalse(live.atStation)
        assertFalse(live.terminated)
        assertFalse(live.cancelled || live.partiallyCancelled || live.diverted)
        assertEquals(at2018(15, 16, 41), live.lastSeenAt)
        assertEquals(at2018(15, 16, 50).toEpochMilli(), live.updatedAt)
        assertNull(live.trainName)
        assertEquals(1, live.boardingDay)
    }

    @Test fun theSampleRouteHasSourceAndDestinationAndRollsPastMidnight() {
        val live = found(TrainFixtures.documentationSample, sampleTrain, at2018(15, 16, 50).toEpochMilli(), sampleStart)
        val first = live.stops.first()
        assertNull(first.scheduledArrival)
        assertEquals(at2018(15, 8, 25), first.scheduledDeparture)
        assertEquals(at2018(15, 8, 25), first.actualDeparture)
        val last = live.stops.last()
        assertNull(last.scheduledDeparture)
        assertNull(last.expectedDeparture)
        // Lucknow 09:00PM, then New Delhi 05:30AM: the next morning, although the reply says Day "0" for both.
        assertEquals(at2018(15, 21, 0), live.stops.first { it.code == "LKO" }.scheduledArrival)
        assertEquals(at2018(16, 5, 30), last.scheduledArrival)
        assertEquals(1, live.stops.first { it.code == "LKO" }.day)
        assertEquals(2, last.day)
    }

    @Test fun reachedStopsHaveActualTimesAndStopsAheadExpectedOnes() {
        val live = found(TrainFixtures.documentationSample, sampleTrain, at2018(15, 16, 50).toEpochMilli(), sampleStart)
        val mfp = live.stops.first { it.code == "MFP" }
        assertEquals(at2018(15, 10, 25), mfp.scheduledArrival)
        assertEquals(at2018(15, 10, 50), mfp.actualArrival)
        assertNull(mfp.expectedArrival)
        val gkp = live.stops.first { it.code == "GKP" }
        assertEquals(at2018(15, 16, 24), gkp.actualDeparture)
        val lko = live.stops.first { it.code == "LKO" }
        assertNull(lko.actualArrival)
        assertEquals(at2018(15, 21, 0), lko.expectedArrival)
        assertEquals(at2018(15, 21, 10), lko.expectedDeparture)
        val jtb = live.stops.first { it.code == "JTB" }
        assertEquals(at2018(15, 16, 27), jtb.scheduledArrival)
        assertEquals(at2018(15, 16, 41), jtb.actualArrival)
    }

    @Test fun theBoardingStationOfTheSampleKnowsItsRunDay() {
        assertEquals(1, found(TrainFixtures.documentationSample, sampleTrain, at2018(15, 16, 50).toEpochMilli(), sampleStart).boardingDay)
        val delhi = sampleTrain.copy(from = Place("NDLS", "New Delhi"))
        assertEquals(2, found(TrainFixtures.documentationSample, delhi, at2018(15, 16, 50).toEpochMilli(), sampleStart).boardingDay)
    }

    @Test fun beforeTheTrainHasLeftTheCurrentStationItIsAtTheStation() {
        val live = found(TrainFixtures.documentationSample, sampleTrain, at2018(15, 16, 30).toEpochMilli(), sampleStart)
        assertEquals("JTB", live.lastStationCode)
        assertTrue(live.atStation)
        assertEquals(14, live.delayMinutes)
        assertEquals(at2018(15, 16, 41), live.lastSeenAt)
        // Its actual departure is still ahead, so it is an expected time, not an actual one.
        val jtb = live.stops.first { it.code == "JTB" }
        assertNull(jtb.actualDeparture)
        assertEquals(at2018(15, 16, 41), jtb.expectedDeparture)
    }

    // ---- hand-built replies for 12633

    @Test fun aRunningTrainWithItsRoute() {
        val live = found(TrainFixtures.running)
        assertEquals(7, live.stops.size)
        assertEquals("VM", live.lastStationCode)
        assertEquals("Villupuram Jn", live.lastStationName)
        assertEquals(12, live.delayMinutes)
        assertTrue(live.started)
        assertFalse(live.atStation)
        assertFalse(live.cancelled)
        assertEquals(fetched, live.updatedAt)
        assertEquals(ist(12, 23, 54), live.lastSeenAt)
        val ms = live.stops[0]
        assertEquals(ist(12, 21, 30), ms.scheduledDeparture)
        assertEquals(ist(12, 21, 42), ms.actualDeparture)
        assertNull(ms.scheduledArrival)
        assertNull(ms.platform)
    }

    @Test fun stopsAfterMidnightFallOnTheNextDay() {
        val live = found(TrainFixtures.running)
        val vri = live.stops[4]
        assertEquals(2, vri.day)
        assertEquals(ist(13, 0, 45), vri.scheduledArrival)
        assertEquals(ist(13, 0, 57), vri.expectedArrival)
        assertNull(vri.actualArrival)
        assertEquals(ist(13, 4, 35), live.stops.last().scheduledArrival)
        assertEquals(ist(13, 4, 47), live.stops.last().expectedArrival)
    }

    @Test fun anActualTimeAfterMidnightOfAStopScheduledBeforeIt() {
        val json = """{"ResponseCode":"200","StartDate":"12-11-2026","TrainNumber":"12633","CurrentStation":${TrainFixtures.stop(1, "VM", "Villupuram Jn", "23:50", "23:55", "00:10", "00:14", "20 M", "19 M")},"TrainRoute":[${TrainFixtures.stop(0, "MS", "Chennai Egmore", null, "21:30")},${TrainFixtures.stop(1, "VM", "Villupuram Jn", "23:50", "23:55", "00:10", "00:14", "20 M", "19 M")},${TrainFixtures.stop(2, "TPJ", "Tiruchirappalli Jn", "04:35", null, "04:55", null, "20 M")}],"Message":"SUCCESS"}"""
        val live = found(json, at = ist(13, 0, 30).toEpochMilli())
        val vm = live.stops[1]
        assertEquals(ist(12, 23, 50), vm.scheduledArrival)
        assertEquals(ist(13, 0, 10), vm.actualArrival)
        assertEquals(ist(13, 0, 14), vm.actualDeparture)
        assertEquals(19, live.delayMinutes)
        assertEquals(ist(13, 4, 55), live.stops[2].expectedArrival)
    }

    @Test fun aDepartureEarlierThanTheArrivalAtTheSameStopIsAfterMidnight() {
        val json = """{"ResponseCode":"200","StartDate":"12-11-2026","TrainNumber":"12633","CurrentStation":null,"TrainRoute":[${TrainFixtures.stop(0, "MS", "Chennai Egmore", null, "21:30")},${TrainFixtures.stop(1, "VM", "Villupuram Jn", "23:55", "00:05")},${TrainFixtures.stop(2, "TPJ", "Tiruchirappalli Jn", "04:35", null)}],"Message":"SUCCESS"}"""
        val live = found(json)
        assertEquals(ist(12, 23, 55), live.stops[1].scheduledArrival)
        assertEquals(ist(13, 0, 5), live.stops[1].scheduledDeparture)
        assertEquals(ist(13, 4, 35), live.stops[2].scheduledArrival)
        assertEquals(2, live.stops[2].day)
    }

    @Test fun theDayFieldMovesAStopLaterWhenTheClockCannotSayItHasWrapped() {
        // Two stops 27 hours apart, with only the Day field to say so; and the field counts from its own first value (here 1).
        fun s(code: String, day: Int, sa: String?, sd: String?) =
            TrainFixtures.stop(0, code, code, sa, sd).replace("\"Day\":\"0\"", "\"Day\":\"$day\"")
        val json = """{"ResponseCode":"200","StartDate":"12-11-2026","TrainNumber":"12633","CurrentStation":null,"TrainRoute":[${s("MS", 1, null, "18:00")},${s("VM", 1, "20:00", "20:02")},${s("TPJ", 2, "23:00", null)}],"Message":"SUCCESS"}"""
        val live = found(json)
        assertEquals(ist(12, 20, 0), live.stops[1].scheduledArrival)
        assertEquals(ist(13, 23, 0), live.stops[2].scheduledArrival)
        assertEquals(2, live.stops[2].day)
    }

    @Test fun noCurrentStationMeansNotStartedAndEveryTimeIsExpected() {
        val n = found(TrainFixtures.notStarted)
        assertFalse(n.started)
        assertFalse(n.atStation)
        assertNull(n.lastStationCode)
        assertNull(n.lastSeenAt)
        assertEquals(0, n.delayMinutes)
        assertEquals(ist(12, 21, 30), n.stops[0].expectedDeparture)
        assertNull(n.stops[0].actualDeparture)
    }

    @Test fun arrivedAtTheDestination() {
        val live = found(TrainFixtures.arrived, at = ist(13, 4, 50).toEpochMilli())
        assertTrue(live.terminated)
        assertFalse(live.atStation)
        assertEquals("TPJ", live.lastStationCode)
        assertEquals(ist(13, 4, 41), live.stops.last().actualArrival)
        assertEquals(6, live.delayMinutes)
    }

    @Test fun theBoardingDayIsLearnedFromTheRoute() {
        assertEquals(1, found(TrainFixtures.running).boardingDay)
        assertEquals(2, found(TrainFixtures.running, train.copy(from = Place("VRI", "Vriddhachalam"))).boardingDay)
    }

    @Test fun cancelledAndDivertedAreNeverInventedFromAReplyThatDoesNotSayThem() {
        for (json in listOf(TrainFixtures.running, TrainFixtures.notStarted, TrainFixtures.arrived, TrainFixtures.documentationSample)) {
            val l = (IndianRailApiParser.parse(json, train.copy(number = if (json == TrainFixtures.documentationSample) "12565" else "12633"), if (json == TrainFixtures.documentationSample) sampleStart else DOJ, fetched) as TrainLookup.Found).live
            assertFalse(l.cancelled || l.partiallyCancelled || l.diverted)
        }
    }

    // ---- delays and clocks

    @Test fun twelveHourClocks() {
        val c = IndianRailApiParser::clock
        assertEquals(LocalTime.of(16, 27), c("04:27PM"))
        assertEquals(LocalTime.of(0, 5), c("12:05AM"))
        assertEquals(LocalTime.of(12, 30), c("12:30PM"))
        assertEquals(LocalTime.of(0, 0), c("12:00AM"))
        assertEquals(LocalTime.of(12, 0), c("12:00PM"))
        assertEquals(LocalTime.of(1, 5), c("01:05AM"))
        assertEquals(LocalTime.of(23, 59), c("11:59PM"))
        assertEquals(LocalTime.of(9, 15), c("9:15 am"))
        for (bad in listOf("Source", "Destination", "-", "", "  ", "00:30AM", "13:00PM", "10:61AM", "16:27", "04:27", null)) assertNull("$bad", c(bad))
    }

    @Test fun delayStrings() {
        val d = IndianRailApiParser::delayMinutes
        assertEquals(14, d("14 M"))
        assertEquals(0, d("00 M"))
        assertEquals(75, d("75 M"))
        assertEquals(65, d("1 H 5 M"))
        assertEquals(120, d("2 H"))
        assertEquals(-5, d("-5 M"))
        for (unknown in listOf("-", "", "On time", "RT", "14", "late", "M", null)) assertNull("$unknown", d(unknown))
    }

    @Test fun aDelayOverAnHourInMinutes() {
        val json = TrainFixtures.running.replace("12 M", "75 M")
        assertEquals(75, found(json).delayMinutes)
    }

    @Test fun anUnknownDelayFormatIsWorkedOutFromTheTimesOrLeftAtZero() {
        val odd = TrainFixtures.running.replace("12 M", "about twelve")
        // The times still say 12 minutes: reported minus scheduled.
        assertEquals(12, found(odd).delayMinutes)
        // No times and no delay: unknown stays 0 rather than a guess.
        val none = """{"ResponseCode":"200","StartDate":"12-11-2026","TrainNumber":"12633","CurrentStation":{"StationCode":"VM","StationName":"Villupuram Jn","ScheduleArrival":"11:40PM","ActualArrival":"-","DelayInArrival":"-","ScheduleDeparture":"11:42PM","ActualDeparture":"-","DelayInDeparture":"-"},"TrainRoute":[${TrainFixtures.stop(0, "MS", "Chennai Egmore", null, "21:30")},{"StationCode":"VM","StationName":"Villupuram Jn","ScheduleArrival":"11:40PM","ScheduleDeparture":"11:42PM"},${TrainFixtures.stop(2, "TPJ", "Tiruchirappalli Jn", "04:35", null)}],"Message":"SUCCESS"}"""
        val live = found(none)
        assertEquals("VM", live.lastStationCode)
        assertEquals(0, live.delayMinutes)
    }

    // ---- answers that are not a running train

    @Test fun anyResponseCodeButTwoHundredIsNotAStatus() {
        assertEquals(TrainLookup.Refused(LiveFailure.KEY_REJECTED), IndianRailApiParser.parse(TrainFixtures.keyRejected, train, DOJ, fetched))
        assertEquals(TrainLookup.Refused(LiveFailure.LIMIT_REACHED), IndianRailApiParser.parse(TrainFixtures.limitReached, train, DOJ, fetched))
        assertEquals(TrainLookup.Refused(LiveFailure.PROVIDER), IndianRailApiParser.parse(TrainFixtures.unknownRefusal, train, DOJ, fetched))
        assertEquals(TrainLookup.NotFound, IndianRailApiParser.parse(TrainFixtures.noData, train, DOJ, fetched))
        // A numeric code is read like a string one, and a code with no message is unknown.
        assertEquals(TrainLookup.Refused(LiveFailure.PROVIDER), IndianRailApiParser.parse("""{"ResponseCode":201}""", train, DOJ, fetched))
        assertEquals(TrainLookup.Refused(LiveFailure.PROVIDER), IndianRailApiParser.parse("""{"ResponseCode":"200x","Message":"hello"}""", train, DOJ, fetched))
        // The limit wins over the key when a message names both.
        assertEquals(TrainLookup.Refused(LiveFailure.LIMIT_REACHED), IndianRailApiParser.parse("""{"ResponseCode":"403","Message":"Limit exceeded for this API key"}""", train, DOJ, fetched))
    }

    @Test fun aNumericTwoHundredIsSuccessToo() {
        val json = TrainFixtures.notStarted.replace("\"ResponseCode\":\"200\"", "\"ResponseCode\":200")
        assertTrue(IndianRailApiParser.parse(json, train, DOJ, fetched) is TrainLookup.Found)
    }

    @Test fun aMissingRouteIsUnreadableAndAnEmptyOneIsNotFound() {
        assertEquals(TrainLookup.Unreadable, IndianRailApiParser.parse("""{"ResponseCode":"200","TrainNumber":"12633","Message":"SUCCESS"}""", train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, IndianRailApiParser.parse("""{"ResponseCode":"200","TrainRoute":"none"}""", train, DOJ, fetched))
        assertEquals(TrainLookup.NotFound, IndianRailApiParser.parse("""{"ResponseCode":"200","TrainNumber":"12633","TrainRoute":[]}""", train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, IndianRailApiParser.parse("""{"ResponseCode":"200","TrainRoute":[1,2,{"StationName":"x"}]}""", train, DOJ, fetched))
    }

    @Test fun aNullCurrentStationIsNotAnError() {
        val json = TrainFixtures.running.replace(Regex("\"CurrentStation\":\\{[^}]*\\}"), "\"CurrentStation\":null")
        val live = found(json)
        assertNull(live.lastStationCode)
        assertFalse(live.started)
        assertEquals(7, live.stops.size)
    }

    @Test fun anotherTrainAnotherStartDateOrAnythingElse() {
        assertEquals(TrainLookup.Unreadable, IndianRailApiParser.parse(TrainFixtures.otherTrain, train, DOJ, fetched))
        assertEquals(TrainLookup.NotFound, IndianRailApiParser.parse(TrainFixtures.running, train, DOJ.minusDays(1), fetched))
        assertEquals(TrainLookup.Unreadable, IndianRailApiParser.parse("<html>blocked</html>", train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, IndianRailApiParser.parse("{}", train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, IndianRailApiParser.parse("[1,2]", train, DOJ, fetched))
        assertEquals(TrainLookup.Unreadable, IndianRailApiParser.parse("", train, DOJ, fetched))
    }

    // ---- for the developer's check

    @Test fun theMissingFieldIsNamed() {
        assertNull(IndianRailApiParser.firstMissingField(TrainFixtures.running))
        assertNull(IndianRailApiParser.firstMissingField(TrainFixtures.documentationSample))
        assertNull(IndianRailApiParser.firstMissingField("<html>"))
        assertNull(IndianRailApiParser.firstMissingField(TrainFixtures.keyRejected))
        assertNull(IndianRailApiParser.firstMissingField("""{"ResponseCode":"200","TrainRoute":[]}"""))
        assertEquals("ResponseCode", IndianRailApiParser.firstMissingField("""{"hello":1}"""))
        assertEquals("TrainRoute", IndianRailApiParser.firstMissingField("""{"ResponseCode":"200"}"""))
        assertEquals("StationCode", IndianRailApiParser.firstMissingField("""{"ResponseCode":"200","TrainRoute":[{"StationName":"x"}]}"""))
        assertEquals("ScheduleArrival (or ScheduleDeparture)", IndianRailApiParser.firstMissingField("""{"ResponseCode":"200","TrainRoute":[{"StationCode":"MS"}]}"""))
        assertEquals("top-level object", IndianRailApiParser.firstMissingField("[1]"))
        assertEquals("TrainRoute entries as objects", IndianRailApiParser.firstMissingField("""{"ResponseCode":"200","TrainRoute":[1]}"""))
    }

    @Test fun theProvidersOwnWordsAreShortAndOnOneLine() {
        assertEquals("ResponseCode 200 · Message \"SUCCESS\"", IndianRailApiParser.providerNote(TrainFixtures.documentationSample))
        assertEquals("ResponseCode 401 · Message \"Invalid API key\"", IndianRailApiParser.providerNote(TrainFixtures.keyRejected))
        val long = IndianRailApiParser.providerNote("""{"ResponseCode":"500","Message":"${"x".repeat(300)}\n\nmore"}""")
        assertNotNull(long)
        assertTrue(long!!.length < 120 && '\n' !in long)
        assertNull(IndianRailApiParser.providerNote("<html>"))
        assertNull(IndianRailApiParser.providerNote("""{"a":1}"""))
    }
}
