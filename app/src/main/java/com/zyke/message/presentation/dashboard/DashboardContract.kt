package com.zyke.message.presentation.dashboard

import com.zyke.message.domain.cards.CardCycles
import com.zyke.message.domain.dashboard.CardBills
import com.zyke.message.domain.dashboard.CategoryLimits
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanInsights
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.dashboard.ReportCard
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.time.Instant

object DashboardContract {
    const val CARDS_TIP = "tip-cards"

    data class State(
        val isLoading: Boolean = true,
        val permissionMissing: Boolean = false,
        val hasError: Boolean = false,

        /** True once the first inbox snapshot has arrived. */
        val hasLoaded: Boolean = false,
        val messages: List<ClassifiedMessage> = emptyList(),
        val period: DashboardPeriod = DashboardPeriod.THIS_MONTH,
        /** The days chosen by hand, used when [period] is Custom range. */
        val customRange: com.zyke.message.domain.dashboard.DateRange? = null,
        /** How many pay cycles back the chart has been moved: 0 is the current one, -1 the one before. */
        val cycleOffset: Int = 0,
        /** Where the person's month starts, from their settings. */
        val cycle: com.zyke.message.domain.cycle.CycleRule = com.zyke.message.domain.cycle.CycleRule.CALENDAR,
        val direction: FlowDirection = FlowDirection.SPENT,
        val category: MessageCategory? = null,
        val now: Instant = Instant.EPOCH,

        val budgets: Map<MessageCategory, BigDecimal> = emptyMap(),
        /** Category whose budget is being edited, if the dialog is open. */
        val editingBudget: MessageCategory? = null,

        val showFilterSheet: Boolean = false,
        /** Which payments the figures cover: all, UPI, one account or one card. */
        val source: com.zyke.message.domain.dashboard.SourceFilter = com.zyke.message.domain.dashboard.SourceFilter(),
        val sourceOptions: List<com.zyke.message.domain.dashboard.SourceOption> = emptyList(),
        val showReportCard: Boolean = false,
        val expandedVendor: String? = null,
        /** The category whose payments are open in a sheet, and what they add up to. */
        val openCategory: MessageCategory? = null,
        val categoryDetail: Dashboard? = null,
        val showAllVendors: Boolean = false,

        val dashboard: Dashboard? = null,
        /** Money in and money out over the chosen period, for the line chart; independent of direction and category. */
        val expenseTrend: List<com.zyke.message.domain.dashboard.TrendPoint> = emptyList(),
        val incomeTrend: List<com.zyke.message.domain.dashboard.TrendPoint> = emptyList(),
        /** This month as a report card; independent of the period and filters above. */
        val reportCard: ReportCard? = null,
        /** Credit card statements by card; independent of the selected period. */
        val cardBills: List<CardBills> = emptyList(),
        val expandedCard: String? = null,
        /** The card or account whose cycles are open in a sheet. */
        val expandedCardCycle: String? = null,
        /** The cards the person added in Settings, and their billing cycles. */
        val cards: List<UserCard> = emptyList(),
        val cardCycles: List<CardCycles> = emptyList(),
        /** Daily limits; the monthly ones are [budgets]. */
        val dailyBudgets: Map<MessageCategory, BigDecimal> = emptyMap(),
        /** The person's money plan, and how this month is going against it; null insights when no plan is filled in. */
        val plan: MoneyPlan = MoneyPlan(),
        val planInsights: PlanInsights? = null,
        val limitSuggestions: List<com.zyke.message.domain.forecast.LimitSuggestion> = emptyList(),
        val forecast: com.zyke.message.domain.forecast.SpendForecast? = null,
        /** Where each category with a limit stands today and this month. */
        val limits: List<CategoryLimits> = emptyList(),
        /** Tips the person has waved away. */
        val dismissedTips: Set<String> = emptySet()
    ) {
        /** Nudge towards Settings until they add a card or say not now. */
        val showCardsPrompt: Boolean get() = hasLoaded && cards.isEmpty() && CARDS_TIP !in dismissedTips

        val query get() = DashboardQuery(period, direction, category, customRange)

        /** Period and category live in the filter sheet; the direction has its own switch on screen. */
        val activeFilterCount: Int
            get() = (if (period != DashboardPeriod.THIS_MONTH && cycleOffset == 0) 1 else 0) + (if (category != null) 1 else 0) + (if (source.isActive) 1 else 0)
    }

    sealed interface Intent {
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class SelectPeriod(val period: DashboardPeriod) : Intent
        data class SelectSource(val source: com.zyke.message.domain.dashboard.SourceFilter) : Intent
        /** Moves the chart and lists to the pay cycle before or after; never past the current one. */
        data class ShiftCycle(val delta: Int) : Intent
        data class SelectCustomRange(val start: java.time.LocalDate, val end: java.time.LocalDate) : Intent
        data class SelectDirection(val direction: FlowDirection) : Intent
        /** Tapping the selected category again clears it. */
        data class SelectCategory(val category: MessageCategory) : Intent
        data class OpenCategory(val category: MessageCategory) : Intent
        data object CloseCategory : Intent
        data object ClearCategory : Intent
        data class SetCategory(val category: MessageCategory?) : Intent
        data object OpenReportCard : Intent
        data object CloseReportCard : Intent
        data object OpenFilters : Intent
        data object CloseFilters : Intent
        data object ResetFilters : Intent
        data class ToggleVendor(val key: String) : Intent
        data object ToggleShowAllVendors : Intent
        /** Try again after the inbox could not be read. */
        data object Retry : Intent
        data class ToggleCard(val key: String) : Intent
        data class ToggleCardCycle(val id: String) : Intent
        data class DismissTip(val key: String) : Intent
        data class OpenLink(val url: String) : Intent
        data class EditBudget(val category: MessageCategory?) : Intent
        /** [text] is whatever the user typed; blank removes the budget. */
        data class SaveBudget(val category: MessageCategory, val text: String) : Intent
    }

    sealed interface Effect {
        data class OpenLink(val url: String) : Effect
    }
}
