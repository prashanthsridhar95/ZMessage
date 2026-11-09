package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import com.zyke.message.domain.train.TrainFixtures.DOJ
import com.zyke.message.domain.train.TrainFixtures.ist
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTrainsTest {
    private val today = LocalDate.of(2026, 11, 9)

    @Test fun theFormChecksEachField() {
        val ok = ManualTrainRules.check("12633", DOJ, "", "", today) as ManualTrainCheck.Ok
        assertEquals("12633", ok.number)
        assertNull(ok.boarding)
        fun problem(n: String, d: LocalDate?, s: String = "", p: String = "") = (ManualTrainRules.check(n, d, s, p, today) as ManualTrainCheck.Problem)
        assertEquals("number", problem("", DOJ).field)
        assertEquals("number", problem("12A33", DOJ).field)
        assertEquals("number", problem("1263", DOJ).field)
        assertEquals("number", problem("126334", DOJ).field)
        assertEquals("date", problem("12633", null).field)
        assertEquals("That day has passed", problem("12633", today.minusDays(2)).message)
        assertEquals("date", problem("12633", today.plusDays(400)).field)
        assertEquals("station", problem("12633", DOJ, "12345").field)
        assertEquals("pnr", problem("12633", DOJ, "", "12AB").field)
        assertEquals("pnr", problem("12633", DOJ, "", "12345678901").field)
        assertTrue(ManualTrainRules.check("12633", today.minusDays(1), "", "", today) is ManualTrainCheck.Ok)
        assertTrue(ManualTrainRules.check(" 126-33 ", DOJ, "", "", today) is ManualTrainCheck.Ok)
    }

    @Test fun aStationIsACodeOrAName() {
        assertEquals("NDLS", ManualTrainRules.station("ndls")?.code)
        assertEquals("MS", ManualTrainRules.station("Chennai Egmore")?.code)
        assertEquals("TPJ", ManualTrainRules.station("Trichy Jn")?.code)
        assertEquals("ABCD", ManualTrainRules.station("abcd")?.code)
        assertNull(ManualTrainRules.station("Nowhere At All"))
        assertNull(ManualTrainRules.station(""))
        assertNull(ManualTrainRules.stationProblem(""))
        assertEquals("Use the station code (like MAS) or its name", ManualTrainRules.stationProblem("1"))
        val ok = ManualTrainRules.check("12633", DOJ, "Villupuram", "123456", today) as ManualTrainCheck.Ok
        assertEquals("VM", ok.boarding?.code)
        assertEquals("123456", ok.pnrNote)
    }

    @Test fun aHandMadeJourneyAndATextAreOneJourneyAndTheTextWins() {
        val text = BookedTrain("12633", null, DOJ, Place("MS", "Chennai Egmore"), null, LocalTime.of(21, 30), null, coach = "S4", berth = "32 SU", platform = "4")
        val mine = ManualTrain("m1", "12633", DOJ, "MS", "Chennai Egmore", "123", name = "Kanniyakumari Express", destinationCode = "TPJ", destinationName = "Tiruchirappalli",
            departure = LocalTime.of(21, 31), arrival = LocalTime.of(4, 35), arrivalDayOffset = 1, confirmed = true)
        val merged = ManualTrains.merge(listOf(text), listOf(mine)).single()
        assertEquals("Kanniyakumari Express", merged.name)
        assertEquals(LocalTime.of(21, 30), merged.departure)
        assertEquals(LocalTime.of(4, 35), merged.arrival)
        assertEquals("TPJ", merged.to?.code)
        assertEquals("S4", merged.coach)
        assertEquals("123", merged.pnr)
        assertEquals(2, ManualTrains.merge(listOf(text), listOf(mine.copy(number = "12000"))).size)
    }

    @Test fun aJourneyExpiresADayAfterItShouldHaveArrived() {
        val m = ManualTrain("m1", "12633", DOJ, departure = LocalTime.of(21, 30), arrival = LocalTime.of(4, 35))
        assertFalse(ManualTrains.isExpired(m, ist(14, 4, 0)))
        assertTrue(ManualTrains.isExpired(m, ist(14, 4, 36)))
        val bare = ManualTrain("m2", "12633", DOJ)
        assertFalse(ManualTrains.isExpired(bare, ist(14, 23, 0)))
        assertTrue(ManualTrains.isExpired(bare, ist(15, 0, 1) .plusSeconds(24 * 3600)))
    }

    @Test fun aLookUpFillsTheNameTheStationsAndTheSchedule() {
        val live = (IndianRailApiParser.parse(TrainFixtures.running, BookedTrain("12633", null, DOJ, null, null, null, null), DOJ, 0L) as TrainLookup.Found).live
        val m = ManualTrains.confirmed(ManualTrain("m1", "12633", DOJ), live)
        assertNull("the provider sends no train name", m.name)
        assertEquals("MS", m.boardingCode)
        assertEquals("TPJ", m.destinationCode)
        assertEquals(LocalTime.of(21, 30), m.departure)
        assertEquals(LocalTime.of(4, 35), m.arrival)
        assertEquals(1, m.arrivalDayOffset)
        assertTrue(m.confirmed)
        val mid = ManualTrains.confirmed(ManualTrain("m1", "12633", DOJ, boardingCode = "VRI"), live)
        assertEquals(LocalTime.of(0, 47), mid.departure)
        assertEquals(2, mid.boardingDay)
        assertEquals("Vriddhachalam Jn", mid.boardingName ?: "Vriddhachalam Jn")
        assertEquals("Kanyakumari Express", ManualTrains.prettyName("KANYAKUMARI EXP"))
    }

    @Test fun theStoreSavesRemovesAndPrunesUnderOneLock() {
        val saved = ArrayList<List<ManualTrain>>()
        val store = ManualTrainStore(emptyList()) { saved += it }
        val a = ManualTrain("a", "12633", DOJ, addedAt = 1)
        store.save(a)
        store.save(a.copy(id = "b"))
        assertEquals(listOf("b"), store.trains.value.map { it.id })
        store.save(ManualTrain("c", "12000", DOJ.minusDays(5), departure = LocalTime.of(1, 0), arrival = LocalTime.of(5, 0)))
        assertEquals(2, store.trains.value.size)
        assertEquals(1, store.prune(ist(12, 12, 0)))
        assertEquals("b", store.remove("b")?.id)
        assertNull(store.remove("b"))
        assertTrue(store.trains.value.isEmpty())
        assertTrue(saved.isNotEmpty())
    }

    @Test fun thePlanCodecKeepsWhatTrackingNeedsAndNoPnr() {
        val t = BookedTrain("12633", "Kanniyakumari Express", DOJ, Place("MS", "Chennai Egmore"), Place("TPJ", "Tiruchirappalli"), LocalTime.of(21, 30), LocalTime.of(4, 35),
            1, "SL", "S4", "32 SU", "CNF", "1234567890", "4", TrainStatusKind.RUNNING_LATE, 45, LocalTime.of(22, 15), true, false, 2)
        val text = TrainPlanCodec.encode(listOf(t))
        assertFalse("1234567890" in text)
        val back = TrainPlanCodec.decode(text).single()
        assertEquals(t.copy(pnr = null, chartPrepared = false), back)
        assertTrue(TrainPlanCodec.decode("garbage\n\n\t\t").isEmpty())
        assertEquals(2, TrainPlanCodec.decode(TrainPlanCodec.encode(listOf(t, t.copy(number = "12001")))).size)
    }
}
