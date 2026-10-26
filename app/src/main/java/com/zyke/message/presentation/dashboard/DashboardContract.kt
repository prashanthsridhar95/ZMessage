package com.zyke.message.presentation.dashboard

import com.zyke.message.domain.dashboard.CardBills
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.time.Instant

object DashboardContract {

    data class State(
        val isLoading: Boolean = true,
        val permissionMissing: Boolean = false,
        val hasError: Boolean = false,

        /** True once the first inbox snapshot has arrived. */
        val hasLoaded: Boolean = false,
        val messages: List<ClassifiedMessage> = emptyList(),
        val period: DashboardPeriod = DashboardPeriod.THIS_MONTH,
        val direction: FlowDirection = FlowDirection.SPENT,
        val category: MessageCategory? = null,
        val now: Instant = Instant.EPOCH,

        val budgets: Map<MessageCategory, BigDecimal> = emptyMap(),
        /** Category whose budget is being edited, if the dialog is open. */
        val editingBudget: MessageCategory? = null,

        val showFilterSheet: Boolean = false,
        val expandedVendor: String? = null,
        val showAllVendors: Boolean = false,

        val dashboard: Dashboard? = null,
        /** Credit card statements by card; independent of the selected period. */
        val cardBills: List<CardBills> = emptyList(),
        val expandedCard: String? = null
    ) {
        val query get() = DashboardQuery(period, direction, category)

        /** Period and category are "extra" filters; the direction has its own switch on screen. */
        val activeFilterCount: Int
            get() = (if (period != DashboardPeriod.THIS_MONTH) 1 else 0) + (if (category != null) 1 else 0)
    }

    sealed interface Intent {
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class SelectPeriod(val period: DashboardPeriod) : Intent
        data class SelectDirection(val direction: FlowDirection) : Intent
        /** Tapping the selected category again clears it. */
        data class SelectCategory(val category: MessageCategory) : Intent
        data object ClearCategory : Intent
        data class SetCategory(val category: MessageCategory?) : Intent
        data object OpenFilters : Intent
        data object CloseFilters : Intent
        data object ResetFilters : Intent
        data class ToggleVendor(val key: String) : Intent
        data object ToggleShowAllVendors : Intent
        data class ToggleCard(val key: String) : Intent
        data class OpenLink(val url: String) : Intent
        data class EditBudget(val category: MessageCategory?) : Intent
        /** [text] is whatever the user typed; blank removes the budget. */
        data class SaveBudget(val category: MessageCategory, val text: String) : Intent
    }

    sealed interface Effect {
        data class OpenLink(val url: String) : Effect
    }
}
