package com.zyke.message.domain.backup

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupScheduleTest {
    private val utc = ZoneId.of("UTC")
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    private fun local(i: Instant, zone: ZoneId): LocalDateTime = i.atZone(zone).toLocalDateTime()

    @Test fun `daily runs at the chosen time, today if still ahead else tomorrow`() {
        val s = BackupSchedule(BackupFrequency.DAILY, LocalTime.of(2, 0))
        assertEquals(at(utc, 2026, 11, 1, 2), s.nextRun(at(utc, 2026, 11, 1, 1), utc, at(utc, 2026, 10, 31, 2, 5)))
        assertEquals(at(utc, 2026, 11, 2, 2), s.nextRun(at(utc, 2026, 11, 1, 3), utc, at(utc, 2026, 11, 1, 2, 5)))
    }

    @Test fun `the moment of the run itself is not returned again`() {
        val s = BackupSchedule()
        assertEquals(at(utc, 2026, 11, 2, 2), s.nextRun(at(utc, 2026, 11, 1, 2), utc, at(utc, 2026, 11, 1, 2)))
    }

    @Test fun `weekly lands on the chosen weekday`() {
        val s = BackupSchedule(BackupFrequency.WEEKLY, LocalTime.of(2, 0), dayOfWeek = DayOfWeek.SUNDAY)
        // Sunday 1 Nov 2026 is a Sunday.
        assertEquals(at(utc, 2026, 11, 8, 2), s.nextRun(at(utc, 2026, 11, 1, 3), utc, at(utc, 2026, 11, 1, 2, 1)))
        assertEquals(at(utc, 2026, 11, 1, 2), s.nextRun(at(utc, 2026, 10, 28, 12), utc, at(utc, 2026, 10, 25, 2, 1)))
    }

    @Test fun `fortnightly is every other week on a fixed rhythm`() {
        val s = BackupSchedule(BackupFrequency.FORTNIGHTLY, LocalTime.of(2, 0), dayOfWeek = DayOfWeek.SUNDAY)
        val first = s.nextRun(at(utc, 2026, 10, 26), utc, at(utc, 2026, 10, 26))
        val second = s.nextRun(first, utc, first)
        assertEquals(DayOfWeek.SUNDAY, first.atZone(utc).dayOfWeek)
        assertEquals(Duration.ofDays(14), Duration.between(first, second))
    }

    @Test fun `monthly uses the day and clamps to short months`() {
        val s = BackupSchedule(BackupFrequency.MONTHLY, LocalTime.of(2, 0), dayOfMonth = 31)
        assertEquals(at(utc, 2026, 11, 30, 2), s.nextRun(at(utc, 2026, 11, 1), utc, at(utc, 2026, 10, 31, 2, 1)))
        assertEquals(at(utc, 2027, 2, 28, 2), s.nextRun(at(utc, 2027, 2, 1), utc, at(utc, 2027, 1, 31, 2, 1)))
        assertEquals(at(utc, 2028, 2, 29, 2), s.nextRun(at(utc, 2028, 2, 1), utc, at(utc, 2028, 1, 31, 2, 1)))
        assertEquals(at(utc, 2026, 12, 31, 2), s.nextRun(at(utc, 2026, 12, 1), utc, at(utc, 2026, 11, 30, 2, 1)))
    }

    @Test fun `monthly on the 1st crosses the year end`() {
        val s = BackupSchedule(BackupFrequency.MONTHLY, LocalTime.of(2, 0), dayOfMonth = 1)
        assertEquals(at(utc, 2027, 1, 1, 2), s.nextRun(at(utc, 2026, 12, 15), utc, at(utc, 2026, 12, 1, 2, 1)))
    }

    @Test fun `a missed run is due at once`() {
        val s = BackupSchedule(BackupFrequency.DAILY, LocalTime.of(2, 0))
        val now = at(utc, 2026, 11, 5, 9)
        assertEquals(now, s.nextRun(now, utc, at(utc, 2026, 11, 2, 2, 1)))
        val weekly = BackupSchedule(BackupFrequency.WEEKLY, LocalTime.of(2, 0), dayOfWeek = DayOfWeek.SUNDAY)
        assertEquals(now, weekly.nextRun(now, utc, at(utc, 2026, 10, 18, 2, 1)))
        val monthly = BackupSchedule(BackupFrequency.MONTHLY, LocalTime.of(2, 0), dayOfMonth = 1)
        assertEquals(now, monthly.nextRun(now, utc, at(utc, 2026, 9, 1, 2, 1)))
    }

    @Test fun `not missed when the last backup is after the latest scheduled moment`() {
        val weekly = BackupSchedule(BackupFrequency.WEEKLY, LocalTime.of(2, 0), dayOfWeek = DayOfWeek.SUNDAY)
        val now = at(utc, 2026, 11, 5, 9)
        assertEquals(at(utc, 2026, 11, 8, 2), weekly.nextRun(now, utc, at(utc, 2026, 11, 1, 2, 3)))
    }

    @Test fun `no backup yet waits for the next slot`() {
        val s = BackupSchedule()
        assertEquals(at(utc, 2026, 11, 2, 2), s.nextRun(at(utc, 2026, 11, 1, 9), utc, null))
    }

    @Test fun `Kolkata has no daylight saving and keeps half-hour offsets`() {
        val s = BackupSchedule(BackupFrequency.DAILY, LocalTime.of(2, 0))
        val next = s.nextRun(at(kolkata, 2026, 11, 1, 3), kolkata, at(kolkata, 2026, 11, 1, 2, 2))
        assertEquals(LocalDateTime.of(2026, 11, 2, 2, 0), local(next, kolkata))
        assertEquals(Instant.parse("2026-11-01T20:30:00Z"), next)
    }

    @Test fun `spring forward moves a skipped time to just after the gap`() {
        // Europe/Berlin: 29 March 2026, 02:00 -> 03:00; 02:30 does not exist.
        val s = BackupSchedule(BackupFrequency.DAILY, LocalTime.of(2, 30))
        val next = s.nextRun(at(berlin, 2026, 3, 28, 12), berlin, at(berlin, 2026, 3, 28, 2, 31))
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), local(next, berlin))
        // The day after it is back to 02:30.
        assertEquals(LocalDateTime.of(2026, 3, 30, 2, 30), local(s.nextRun(next, berlin, next), berlin))
    }

    @Test fun `fall back uses the first of the two repeated times and stays once a day`() {
        // America/New_York: 1 Nov 2026, 02:00 -> 01:00; 01:30 happens twice.
        val s = BackupSchedule(BackupFrequency.DAILY, LocalTime.of(1, 30))
        val next = s.nextRun(at(newYork, 2026, 10, 31, 12), newYork, at(newYork, 2026, 10, 31, 1, 31))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), next) // EDT, the first 01:30
        val after = s.nextRun(next, newYork, next)
        assertEquals(LocalDateTime.of(2026, 11, 2, 1, 30), local(after, newYork))
    }

    @Test fun `a 2 am daily slot on the DST change day is still one slot`() {
        val s = BackupSchedule(BackupFrequency.DAILY, LocalTime.of(2, 0))
        var t = at(berlin, 2026, 3, 27, 12)
        val slots = (1..5).map { s.nextRun(t, berlin, null).also { n -> t = n } }
        assertEquals(listOf(28, 29, 30, 31, 1), slots.map { it.atZone(berlin).dayOfMonth })
    }

    @Test fun `changing the time of day moves the next run`() {
        val early = BackupSchedule(timeOfDay = LocalTime.of(2, 0))
        val late = BackupSchedule(timeOfDay = LocalTime.of(22, 15))
        val now = at(utc, 2026, 11, 1, 12)
        val last = at(utc, 2026, 11, 1, 2, 1)
        assertEquals(at(utc, 2026, 11, 2, 2), early.nextRun(now, utc, last))
        assertEquals(at(utc, 2026, 11, 1, 22, 15), late.nextRun(now, utc, last))
    }

    @Test fun `changing the phone's zone moves the wall clock slot`() {
        val s = BackupSchedule()
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val last = Instant.parse("2026-11-01T02:01:00Z")
        assertEquals(Instant.parse("2026-11-02T02:00:00Z"), s.nextRun(now, utc, last))
        assertEquals(Instant.parse("2026-11-01T20:30:00Z"), s.nextRun(now, kolkata, Instant.parse("2026-10-31T20:31:00Z")))
    }

    @Test fun `a failure is retried no sooner than six hours later, but never past the next slot`() {
        val s = BackupSchedule()
        val now = at(utc, 2026, 11, 5, 9)
        val failedAt = at(utc, 2026, 11, 5, 8, 59)
        val last = at(utc, 2026, 11, 2, 2, 1)
        assertEquals(failedAt.plus(Duration.ofHours(6)), s.nextAttempt(now, utc, last, failedAt))
        // An old failure does not hold the backup back.
        assertEquals(now, s.nextAttempt(now, utc, last, now.minus(Duration.ofDays(1))))
        // The regular slot wins when it is later than the retry.
        val ok = at(utc, 2026, 11, 5, 3)
        assertEquals(at(utc, 2026, 11, 6, 2), s.nextAttempt(now, utc, ok, failedAt))
    }

    @Test fun `all four frequencies always give a moment after the given one`() {
        val zones = listOf(utc, kolkata, berlin, newYork)
        for (f in BackupFrequency.values()) for (z in zones) for (day in 1..31) {
            val s = BackupSchedule(f, LocalTime.of(2, 30), DayOfWeek.WEDNESDAY, day)
            val now = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, z).plusDays(day.toLong() * 3).toInstant()
            val next = s.nextRun(now, z, s.nextRun(now, z, null))
            assert(!next.isBefore(now))
        }
    }
}
