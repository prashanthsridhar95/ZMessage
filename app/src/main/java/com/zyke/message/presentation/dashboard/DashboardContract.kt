package com.zyke.message.presentation.dashboard

import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import java.time.Instant

object DashboardContract {

    data class State(
        val isLoading: Boolean = true,
        val permissionMissing: Boolean = false,
        val hasError: Boolean = false,

        val messages: List<ClassifiedMessage> = emptyList(),
        val period: DashboardPeriod = DashboardPeriod.THIS_MONTH,
        val direction: FlowDirection = FlowDirection.SPENT,
        val category: MessageCategory? = null,
        val now: Instant = Instant.EPOCH,

        val expandedVendor: String? = null,
        val showAllVendors: Boolean = false,

        val dashboard: Dashboard? = null
    ) {
        val query get() = DashboardQuery(period, direction, category)
    }

    sealed interface Intent {
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class SelectPeriod(val period: DashboardPeriod) : Intent
        data class SelectDirection(val direction: FlowDirection) : Intent
        /** Tapping the selected category again clears it. */
        data class SelectCategory(val category: MessageCategory) : Intent
        data object ClearCategory : Intent
        data class ToggleVendor(val key: String) : Intent
        data object ToggleShowAllVendors : Intent
    }

    sealed interface Effect
}
