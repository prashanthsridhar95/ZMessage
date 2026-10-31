package com.zyke.message.domain.wear

import com.zyke.message.domain.forecast.OutflowSource
import com.zyke.message.domain.forecast.UpcomingOutflow
import com.zyke.message.domain.budget.DailyBudgetStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One bill or EMI coming up. [rupees] is null while amounts are hidden. */
data class WatchBill(val name: String, val rupees: Long?, val inDays: Int)

/**
 * The few figures the paired watch shows, and nothing else: no message text, no sender, no account number.
 *
 * Every amount is whole rupees. With "Hide amounts" on ([masked]) no amount is carried at all, so a hidden figure
 * never leaves the phone; the watch shows dots instead. [toMap] / [fromMap] are the stable wire form (flat string
 * keys, so a Wear Data Layer `DataMap` takes it as is); bump [VERSION] when a key changes meaning.
 */
data class WatchSnapshot(
    val updatedAt: Long,
    /** The day the figures are for (ISO date); the watch dims "today" figures when it is not today any more. */
    val day: String,
    val masked: Boolean,
    /** What is left of today's budget; negative when over; null with no plan. */
    val todayLeft: Long?,
    val monthSpent: Long?,
    val lastMonthSpent: Long?,
    /** This month against last, in percent; null when last month has nothing to compare with. */
    val monthDeltaPercent: Int?,
    val bills: List<WatchBill>,
    val reviewCount: Int,
    val balance: Long?
) {
    /** The same figures ignoring when they were taken: what decides whether the watch needs telling anything. */
    fun sameContent(other: WatchSnapshot) = copy(updatedAt = 0) == other.copy(updatedAt = 0)

    fun isFromToday(today: LocalDate) = day == today.toString()

    fun toMap(): Map<String, String> {
        val map = linkedMapOf("v" to VERSION.toString(), "at" to updatedAt.toString(), "day" to day, "masked" to masked.toString(), "review" to reviewCount.toString())
        todayLeft?.let { map["left"] = it.toString() }
        monthSpent?.let { map["spent"] = it.toString() }
        lastMonthSpent?.let { map["prev"] = it.toString() }
        monthDeltaPercent?.let { map["delta"] = it.toString() }
        balance?.let { map["bal"] = it.toString() }
        map["bills"] = bills.size.toString()
        bills.forEachIndexed { i, bill ->
            map["b${i}n"] = bill.name
            map["b${i}d"] = bill.inDays.toString()
            bill.rupees?.let { map["b${i}a"] = it.toString() }
        }
        return map
    }

    companion object {
        const val VERSION = 1

        /** Where the phone puts it and the watch looks for it (a Data Layer path). */
        const val PATH = "/zyke/snapshot"

        const val MAX_BILLS = 3
        const val NAME_MAX = 24

        /** Even an unchanged snapshot is sent again after this long, so the watch's "updated" time stays honest. */
        const val REFRESH_MS = 30L * 60 * 1000

        private val BILL_SOURCES = setOf(OutflowSource.CARD_BILL, OutflowSource.AUTO_DEBIT, OutflowSource.EMI)

        fun build(
            now: Instant,
            zone: ZoneId,
            hideAmounts: Boolean,
            daily: DailyBudgetStatus?,
            monthSpent: BigDecimal?,
            lastMonthSpent: BigDecimal?,
            outflow: UpcomingOutflow?,
            reviewCount: Int,
            totalBalance: BigDecimal?
        ): WatchSnapshot {
            val today = LocalDate.ofInstant(now, zone)
            fun money(value: BigDecimal?, mode: RoundingMode = RoundingMode.HALF_UP): Long? =
                if (hideAmounts) null else value?.setScale(0, mode)?.toLong()

            val prev = lastMonthSpent?.takeIf { it.signum() > 0 }
            val delta = if (monthSpent != null && prev != null) {
                monthSpent.subtract(prev).multiply(BigDecimal(100)).divide(prev, 0, RoundingMode.HALF_UP).toInt()
            } else null

            val bills = outflow?.items.orEmpty()
                .filter { it.source in BILL_SOURCES && !it.date.isBefore(today) }
                .sortedWith(compareBy({ it.date }, { -it.amount.toDouble() }))
                .take(MAX_BILLS)
                .map { WatchBill(it.title.trim().take(NAME_MAX), money(it.amount), ChronoUnit.DAYS.between(today, it.date).toInt()) }

            return WatchSnapshot(
                updatedAt = now.toEpochMilli(),
                day = today.toString(),
                masked = hideAmounts,
                // Rounded down: never promise more than is left.
                todayLeft = money(daily?.leftToday, RoundingMode.FLOOR),
                monthSpent = money(monthSpent),
                lastMonthSpent = money(lastMonthSpent),
                monthDeltaPercent = if (hideAmounts) null else delta,
                bills = bills,
                reviewCount = reviewCount.coerceAtLeast(0),
                balance = money(totalBalance, RoundingMode.FLOOR)
            )
        }

        /** Null for something that is not a snapshot, or one from a newer version this reader does not know. */
        fun fromMap(map: Map<String, String>): WatchSnapshot? {
            if (map["v"]?.toIntOrNull() != VERSION) return null
            val at = map["at"]?.toLongOrNull() ?: return null
            val day = map["day"] ?: return null
            val count = (map["bills"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_BILLS)
            val bills = (0 until count).map { i ->
                WatchBill(map["b${i}n"] ?: return null, map["b${i}a"]?.toLongOrNull(), map["b${i}d"]?.toIntOrNull() ?: return null)
            }
            return WatchSnapshot(
                updatedAt = at, day = day, masked = map["masked"] == "true",
                todayLeft = map["left"]?.toLongOrNull(), monthSpent = map["spent"]?.toLongOrNull(),
                lastMonthSpent = map["prev"]?.toLongOrNull(), monthDeltaPercent = map["delta"]?.toIntOrNull(),
                bills = bills, reviewCount = map["review"]?.toIntOrNull() ?: 0, balance = map["bal"]?.toLongOrNull()
            )
        }

        /** Whether the phone should send [next] now: it differs from what the watch has, or the watch's copy is getting old. */
        fun shouldPush(previous: WatchSnapshot?, next: WatchSnapshot): Boolean =
            previous == null || !previous.sameContent(next) || next.updatedAt - previous.updatedAt >= REFRESH_MS
    }
}
