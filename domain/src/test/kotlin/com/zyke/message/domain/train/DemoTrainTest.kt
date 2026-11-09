package com.zyke.message.domain.train

import com.zyke.message.domain.demo.DemoData
import com.zyke.message.domain.demo.DemoTrains
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTrainTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun trainsAt(at: ZonedDateTime, zone: ZoneId = ist): Triple<Instant, List<BookedTrain>, List<BookedTrain>> {
        val now = at.toInstant()
        val data = DemoData.build(now.toEpochMilli(), zone)
        val booked = TrainTracker.bookings(data.messages.map { TrainSms(it.body, it.date) }, now)
        return Triple(now, booked, TrainTracker.today(booked, now))
    }

    @Test fun theDemoHasOneTrainToday_leavingAboutAnHourAhead() {
        val (now, booked, today) = trainsAt(ZonedDateTime.of(2026, 11, 5, 14, 30, 0, 0, ist))
        val t = today.single()
        assertEquals(1, booked.size)
        assertEquals("12633", t.number)
        assertEquals("MS", t.from?.code)
        assertEquals("TPJ", t.to?.code)
        assertEquals("Kanniyakumari Express", t.name?.replace("Kanniyakumari", "Kanniyakumari"))
        val s = TrainTracker.snapshot(t, null, now)
        assertEquals("15:45", TrainText.clock(s.bestDeparture!!))
        assertEquals("S4", t.coach)
    }

    @Test fun theDemoTrainIsThereWhicheverZoneThePhoneIsIn_andNearMidnight() {
        listOf(ZoneId.of("America/New_York"), ZoneId.of("Asia/Dubai"), ZoneId.of("UTC")).forEach { zone ->
            listOf(0, 3, 12, 21, 22, 23).forEach { hour ->
                val (_, _, today) = trainsAt(ZonedDateTime.of(2026, 11, 5, hour, 20, 0, 0, ist).withZoneSameInstant(zone), zone)
                assertEquals("$zone $hour:20", 1, today.size)
            }
        }
    }

    @Test fun theMadeUpStatusMovesWithTheClock() {
        val (_, booked, _) = trainsAt(ZonedDateTime.of(2026, 11, 5, 14, 30, 0, 0, ist))
        val t = booked.single()
        val leaves = TrainTracker.scheduledDeparture(t)!!
        fun kind(minutesAfter: Long): TrainStatusKind {
            val now = leaves.plusSeconds(minutesAfter * 60)
            return TrainTracker.snapshot(t, DemoTrains.live(t, now.toEpochMilli()), now).kind
        }
        assertEquals(TrainStatusKind.RUNNING_LATE, kind(-30))
        assertEquals(TrainStatusKind.AT_STATION, kind(DemoTrains.DELAY_MINUTES + 31L))
        assertEquals(TrainStatusKind.RUNNING_LATE, kind(100))
        assertEquals(TrainStatusKind.ARRIVED, kind(400))
        val mid = leaves.plusSeconds(150 * 60)
        val s = TrainTracker.snapshot(t, DemoTrains.live(t, mid.toEpochMilli()), mid)
        assertTrue(s.departed)
        assertEquals("Villupuram", s.position?.name)
        assertEquals("VRI", s.nextStop?.code)
    }
}
