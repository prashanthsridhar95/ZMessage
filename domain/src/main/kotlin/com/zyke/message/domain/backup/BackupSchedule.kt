package com.zyke.message.domain.backup

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class BackupFrequency { DAILY, WEEKLY, FORTNIGHTLY, MONTHLY }

/**
 * When automatic backups are due. Pure: no clock, no Android. Times are wall-clock in the phone's zone, so "2:00 am"
 * stays 2:00 am across a daylight-saving change (a time that does not exist that day moves to the first one after
 * the gap; one that happens twice uses the first).
 *
 * - [DAILY]: every day at [timeOfDay].
 * - [WEEKLY]: every [dayOfWeek].
 * - [FORTNIGHTLY]: every second [dayOfWeek]. Which weeks count is fixed (counted from 1970), so the answer never
 *   depends on when the person switched it on.
 * - [MONTHLY]: on [dayOfMonth], or the last day of a shorter month (the 31st becomes 30 April, 28 or 29 February).
 */
data class BackupSchedule(
    val frequency: BackupFrequency = BackupFrequency.DAILY,
    val timeOfDay: LocalTime = LocalTime.of(2, 0),
    val dayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    val dayOfMonth: Int = 1
) {
    init { require(dayOfMonth in 1..31) { "dayOfMonth must be 1..31" } }

    /** The scheduled moment on [date], or null when that date is not a backup day. */
    private fun slotOn(date: LocalDate, zone: ZoneId): Instant? {
        val due = when (frequency) {
            BackupFrequency.DAILY -> true
            BackupFrequency.WEEKLY -> date.dayOfWeek == dayOfWeek
            BackupFrequency.FORTNIGHTLY -> date.dayOfWeek == dayOfWeek && Math.floorDiv(date.toEpochDay() + 3, 7L) % 2L == 0L
            BackupFrequency.MONTHLY -> date.dayOfMonth == minOf(dayOfMonth, date.lengthOfMonth())
        }
        return if (due) date.atTime(timeOfDay).atZone(zone).toInstant() else null
    }

    private fun slotsAround(after: Instant, zone: ZoneId): List<Instant> {
        val today = after.atZone(zone).toLocalDate()
        return (-SCAN_BACK..SCAN_FORWARD).mapNotNull { slotOn(today.plusDays(it.toLong()), zone) }.sorted()
    }

    /**
     * The next moment a backup should run. After a run, pass its time as [lastSuccess].
     * When a scheduled moment has already passed without a successful backup since (the phone was off, or had no
     * network), the answer is [after] itself: the missed backup runs at the next opportunity.
     */
    fun nextRun(after: Instant, zone: ZoneId, lastSuccess: Instant?): Instant {
        val slots = slotsAround(after, zone)
        val previous = slots.lastOrNull { !it.isAfter(after) }
        if (lastSuccess != null && previous != null && lastSuccess.isBefore(previous)) return after
        return slots.first { it.isAfter(after) }
    }

    /**
     * Like [nextRun], but a failed attempt is not repeated at once: the earliest answer is [RETRY_AFTER] after
     * [lastFailure], so a backup that cannot work (signed out, no key) does not loop.
     */
    fun nextAttempt(after: Instant, zone: ZoneId, lastSuccess: Instant?, lastFailure: Instant?): Instant {
        val due = nextRun(after, zone, lastSuccess)
        return if (lastFailure == null) due else maxOf(due, lastFailure.plus(RETRY_AFTER))
    }

    companion object {
        val RETRY_AFTER: Duration = Duration.ofHours(6)
        private const val SCAN_BACK = 40
        private const val SCAN_FORWARD = 70
    }
}
