package com.zyke.message.wear

import com.zyke.message.domain.wear.WatchSnapshot
import com.zyke.message.presentation.home.HomeContract
import java.time.Instant
import java.time.ZoneId

/** Builds the watch's snapshot from what Home has already worked out; cheap, nothing is read from messages again. */
internal object WatchSnapshots {
    fun from(state: HomeContract.State, hideAmounts: Boolean, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): WatchSnapshot? {
        if (!state.hasLoaded) return null
        return WatchSnapshot.build(
            now = now, zone = zone, hideAmounts = hideAmounts,
            daily = state.daily,
            monthSpent = state.thisMonth?.total,
            lastMonthSpent = state.thisMonth?.previousTotal,
            outflow = state.outflow,
            reviewCount = state.review.count,
            totalBalance = state.accounts.totalBalance
        )
    }
}
