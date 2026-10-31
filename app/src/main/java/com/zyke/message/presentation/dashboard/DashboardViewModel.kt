package com.zyke.message.presentation.dashboard

import com.zyke.message.domain.cycle.CycleRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.cards.CardCycleBuilder
import com.zyke.message.domain.dashboard.CardBillsBuilder
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.SourceFilter
import com.zyke.message.domain.dashboard.ReportCardBuilder
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.CardRepository
import com.zyke.message.domain.plan.PlanInsightsBuilder
import com.zyke.message.domain.repository.MoneyPlanRepository
import com.zyke.message.domain.repository.NoCards
import com.zyke.message.domain.repository.NoMoneyPlan
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.dashboard.DashboardContract.Effect
import com.zyke.message.presentation.dashboard.DashboardContract.Intent
import com.zyke.message.presentation.dashboard.DashboardContract.State
import com.zyke.message.presentation.mvi.MviViewModel
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val observeMessages: ObserveMessagesUseCase,
    private val budgetRepository: BudgetRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val cardRepository: CardRepository = NoCards,
    private val userStateRepository: UserMessageStateRepository? = null,
    private val planRepository: MoneyPlanRepository = NoMoneyPlan,
    private val hiddenAccounts: com.zyke.message.domain.repository.HiddenAccountRepository = com.zyke.message.domain.repository.NoHiddenAccounts,
    private val pfAccounts: com.zyke.message.domain.repository.ProvidentFundAccountRepository = com.zyke.message.domain.repository.NoProvidentFundAccounts,
    private val insightsFilter: com.zyke.message.domain.repository.InsightsFilterRepository = com.zyke.message.domain.repository.NoInsightsFilter,
    /** Where the figures and charts are worked out; never the main thread outside tests. */
    private val deriveContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    private val ioContext: kotlin.coroutines.CoroutineContext = deriveContext
) : MviViewModel<State, Intent, Effect>(
    insightsFilter.load().let { saved ->
        State(
            now = clock.instant(), budgets = budgetRepository.budgets.value, cards = cardRepository.cards.value, plan = planRepository.plan.value,
            period = saved.period, customRange = saved.customRange, source = saved.source, direction = saved.direction
        )
    }
) {

    private var observeJob: Job? = null

    init {
        startDeriving("dashboard", deriveContext) { snapshot -> derive(snapshot) }
        viewModelScope.launch {
            budgetRepository.budgets.collect { budgets -> reduceDashboard { copy(budgets = budgets) } }
        }
        viewModelScope.launch {
            planRepository.plan.collect { plan -> reduceDashboard { copy(plan = plan) } }
        }
        viewModelScope.launch {
            budgetRepository.dailyBudgets.collect { daily -> reduceDashboard { copy(dailyBudgets = daily) } }
        }
        viewModelScope.launch {
            budgetRepository.adjustments.collect { adjustments -> reduceDashboard { copy(budgetAdjustments = adjustments) } }
        }
        userStateRepository?.let { repository ->
            viewModelScope.launch {
                repository.state.collect { userState -> reduce { copy(dismissedTips = userState.dismissedAttention) } }
            }
        }
        viewModelScope.launch {
            cardRepository.cards.collect { cards -> reduceDashboard { copy(cards = cards) } }
        }
    }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted ->
                if (intent.permissionGranted) startObserving() else reduce { copy(isLoading = false, permissionMissing = true) }

            is Intent.SelectCustomRange -> {
                reduceDashboard {
                    copy(period = DashboardPeriod.CUSTOM, customRange = com.zyke.message.domain.dashboard.DateRange(intent.start, intent.end), cycleOffset = 0, expandedVendor = null)
                }
                saveFilter()
            }
            is Intent.SelectPeriod -> {
                reduceDashboard { copy(period = intent.period, cycleOffset = 0, expandedVendor = null) }
                saveFilter()
            }
            is Intent.SelectSource -> {
                reduceDashboard { copy(source = intent.source, expandedVendor = null) }
                saveFilter()
            }
            is Intent.ShiftCycle -> reduceDashboard {
                val offset = (cycleOffset + intent.delta).coerceAtMost(0)
                val today = LocalDate.ofInstant(now, clock.zone)
                if (offset == 0) {
                    copy(period = DashboardPeriod.THIS_MONTH, customRange = null, cycleOffset = 0, expandedVendor = null)
                } else {
                    copy(period = DashboardPeriod.CUSTOM, customRange = cycle.shifted(today, offset), cycleOffset = offset, expandedVendor = null)
                }
            }
            is Intent.SelectDirection -> {
                reduceDashboard { copy(direction = intent.direction, expandedVendor = null, showAllVendors = false) }
                saveFilter()
            }
            is Intent.SelectCategory -> reduceDashboard {
                copy(category = if (category == intent.category) null else intent.category, expandedVendor = null)
            }
            is Intent.SetCategory -> reduceDashboard { copy(category = intent.category, expandedVendor = null) }
            is Intent.OpenCategory -> reduceDashboard { copy(openCategory = intent.category, expandedVendor = null) }
            Intent.CloseCategory -> reduce { copy(openCategory = null, categoryDetail = null, expandedVendor = null) }
            Intent.OpenReportCard -> reduce { copy(showReportCard = true) }
            Intent.CloseReportCard -> reduce { copy(showReportCard = false) }
            Intent.OpenFilters -> reduce { copy(showFilterSheet = true) }
            Intent.CloseFilters -> reduce { copy(showFilterSheet = false) }
            Intent.ResetFilters -> {
                reduceDashboard {
                    copy(period = DashboardPeriod.THIS_MONTH, customRange = null, cycleOffset = 0, category = null, source = SourceFilter(), expandedVendor = null)
                }
                saveFilter()
            }
            Intent.ClearCategory -> reduceDashboard { copy(category = null, expandedVendor = null) }

            is Intent.ToggleVendor -> reduce {
                copy(expandedVendor = if (expandedVendor == intent.key) null else intent.key)
            }
            Intent.Retry -> {
                reduce { copy(hasError = false, isLoading = true) }
                startObserving()
            }
            Intent.ToggleShowAllVendors -> reduce { copy(showAllVendors = !showAllVendors) }
            is Intent.ToggleCardCycle -> reduce { copy(expandedCardCycle = if (expandedCardCycle == intent.id) null else intent.id) }
            is Intent.ToggleCard -> reduce { copy(expandedCard = if (expandedCard == intent.key) null else intent.key) }
            is Intent.DismissTip -> userStateRepository?.dismissAttention(intent.key)
            is Intent.OpenLink -> emitEffect(Effect.OpenLink(intent.url))

            is Intent.EditBudget -> reduce { copy(editingBudget = intent.category) }
            is Intent.SaveBudget -> {
                val amount = intent.text.trim().replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                // Blank or unusable input clears the budget rather than keeping a stale one.
                budgetRepository.setBudget(intent.category, amount)
                reduce { copy(editingBudget = null) }
            }
        }
    }

    private fun startObserving() {
        reduce { copy(permissionMissing = false) }
        if (observeJob?.isActive == true) return

        observeJob = viewModelScope.launch {
            observeMessages.calm()
                .catch { reduce { copy(isLoading = false, hasError = true) } }
                .collect { inbox ->
                    reduceDashboard { copy(isLoading = false, hasError = false, hasLoaded = true, messages = inbox, now = clock.instant()) }
                }
        }
    }

    /** Keeps the period, range and source that are chosen now, so they are still chosen the next time the app opens. */
    private fun saveFilter() {
        val now = currentState()
        // A cycle stepped back to with the arrows is a passing look, not a choice to keep.
        val saved = com.zyke.message.domain.repository.SavedInsightsFilter(
            period = if (now.cycleOffset != 0) DashboardPeriod.THIS_MONTH else now.period,
            customRange = now.customRange.takeIf { now.period == DashboardPeriod.CUSTOM && now.cycleOffset == 0 },
            source = now.source,
            direction = now.direction
        )
        // Written to disk away from the main thread.
        viewModelScope.launch(ioContext) { insightsFilter.save(saved) }
    }

    private fun archivedIds(): Set<Long> = userStateRepository?.state?.value?.archivedIds.orEmpty()

    /** Applies the change at once; the figures and charts follow from the worker thread. */
    private fun reduceDashboard(change: State.() -> State) {
        reduce(change)
        requestDerive()
    }

    private val memos = com.zyke.message.presentation.mvi.PartMemo()

    /** Queries seen lately, so an equal query is the same object each time and can be remembered by identity. */
    private val queriesKept = ArrayList<com.zyke.message.domain.dashboard.DashboardQuery>()

    private fun interned(query: com.zyke.message.domain.dashboard.DashboardQuery): com.zyke.message.domain.dashboard.DashboardQuery {
        queriesKept.firstOrNull { it == query }?.let { return it }
        if (queriesKept.size >= MAX_QUERIES_KEPT) queriesKept.removeAt(0)
        queriesKept += query
        return query
    }

    private suspend fun derive(updated: State): State.() -> State {
        if (!updated.hasLoaded) return { this }
        val started = System.nanoTime()
        memos.begin()
        val zone = clock.zone
        val now = updated.now
        val messages = updated.messages
        val query = interned(updated.query)
        val flowQuery = interned(query.copy(category = null))
        val cycle = memos.of("cycle", updated.plan, messages, now) { CycleRule.resolve(updated.plan, messages, now, zone) }
        // The figures and charts follow the chosen source (UPI, one account or one card); the rest of the screen does not.
        val scoped = memos.of("scoped", messages, updated.source) { com.zyke.message.domain.dashboard.SourceFilters.apply(messages, updated.source) }
        val planInsights = memos.of("planInsights", updated.plan, messages, now, cycle) {
            PlanInsightsBuilder.build(updated.plan, messages, now, zone, cycle)
        }
        val archived = archivedIds()
        val hidden = hiddenAccounts.hidden.value
        val pfKeys = pfAccounts.keys.value
        // Each figure is worked out from the messages and the person's settings alone, so they are worked out side by side,
        // and one that none of its inputs changed for is not worked out again.
        val built = kotlinx.coroutines.coroutineScope {
            fun <T> part(work: () -> T) = async(deriveContext) { work() }
            val categoryDetail = part {
                updated.openCategory?.let { open ->
                    memos.of("categoryDetail", scoped, query, open, now, updated.budgets, cycle) {
                        DashboardBuilder.build(scoped, query.copy(category = open), now, zone, updated.budgets, cycle)
                    }
                }
            }
            val dashboard = part {
                memos.of("dashboard", scoped, query, now, updated.budgets, cycle) {
                    DashboardBuilder.build(scoped, query, now, zone, updated.budgets, cycle)
                }
            }
            val expenseTrend = part {
                memos.of("expenseTrend", scoped, flowQuery, now, updated.budgets, cycle) {
                    DashboardBuilder.build(
                        scoped, flowQuery.copy(direction = com.zyke.message.domain.dashboard.FlowDirection.SPENT), now, zone, updated.budgets, cycle
                    ).trend
                }
            }
            val incomeTrend = part {
                memos.of("incomeTrend", scoped, flowQuery, now, updated.budgets, cycle) {
                    DashboardBuilder.build(
                        scoped, flowQuery.copy(direction = com.zyke.message.domain.dashboard.FlowDirection.RECEIVED), now, zone, updated.budgets, cycle
                    ).trend
                }
            }
            val reportCard = part { memos.of("reportCard", scoped, updated.budgets, now, cycle) { ReportCardBuilder.build(scoped, updated.budgets, now, zone, cycle) } }
            val monthlySummary = part {
                memos.of("monthlySummary", scoped, now, cycle) { com.zyke.message.domain.dashboard.MonthlySummary.build(scoped, now, zone, cycle) }
            }
            val cardBills = part { memos.of("cardBills", messages, now) { CardBillsBuilder.build(messages, LocalDate.ofInstant(now, zone)) } }
            val cardCycles = part { memos.of("cardCycles", messages, updated.cards, now) { CardCycleBuilder.build(messages, updated.cards, now, zone) } }
            val limits = part {
                memos.of("limits", messages, updated.budgets, updated.dailyBudgets, updated.budgetAdjustments, now, cycle) {
                    SpendingLimits.build(messages, updated.budgets, updated.dailyBudgets, now, zone, cycle, updated.budgetAdjustments)
                }
            }
            val sourceOptions = part { memos.of("sourceOptions", messages) { com.zyke.message.domain.dashboard.SourceFilters.options(messages) } }
            val forecast = part {
                // What the bank accounts hold, apart from any provident fund and anything removed as not theirs.
                val balance = memos.of("accountsBalance", messages, archived, updated.cards, now, cycle, hidden, pfKeys) {
                    com.zyke.message.domain.accounts.Accounts.build(
                        if (archived.isEmpty()) messages else messages.filter { it.id !in archived }, updated.cards, now, zone, cycle, hidden, pfKeys
                    ).totalBalance
                }
                memos.of("forecast", messages, balance, now, cycle) { com.zyke.message.domain.forecast.SpendForecasts.build(messages, balance, now, zone, cycle) }
            }
            val ready = Ready(
                categoryDetail.await(), dashboard.await(), expenseTrend.await(), incomeTrend.await(), reportCard.await(), monthlySummary.await(),
                cardBills.await(), cardCycles.await(), limits.await(), sourceOptions.await(), forecast.await()
            )
            ready
        }
        val total = (System.nanoTime() - started) / 1_000_000
        com.zyke.message.data.diag.DevLog.d("dashboard") { "figures rebuilt in $total ms (${messages.size} messages, ${memos.recomputed} of ${memos.size} parts recomputed)" }
        if (total > SLOW_REBUILD_MS) com.zyke.message.data.diag.DevLog.d("dashboard") { "slowest parts: " + memos.slowest(SLOWEST_PARTS_LOGGED) }
        return {
            copy(
                cycle = cycle, categoryDetail = built.categoryDetail, dashboard = built.dashboard,
                expenseTrend = built.expenseTrend, incomeTrend = built.incomeTrend, reportCard = built.reportCard,
                monthlySummary = built.monthlySummary, cardBills = built.cardBills, cardCycles = built.cardCycles,
                limits = built.limits, sourceOptions = built.sourceOptions, planInsights = planInsights,
                forecast = built.forecast
            )
        }
    }

    /** What the figures worked out side by side came to. */
    private class Ready(
        val categoryDetail: com.zyke.message.domain.dashboard.Dashboard?,
        val dashboard: com.zyke.message.domain.dashboard.Dashboard,
        val expenseTrend: List<com.zyke.message.domain.dashboard.TrendPoint>,
        val incomeTrend: List<com.zyke.message.domain.dashboard.TrendPoint>,
        val reportCard: com.zyke.message.domain.dashboard.ReportCard,
        val monthlySummary: com.zyke.message.domain.dashboard.MonthlySummary,
        val cardBills: List<com.zyke.message.domain.dashboard.CardBills>,
        val cardCycles: List<com.zyke.message.domain.cards.CardCycles>,
        val limits: List<com.zyke.message.domain.dashboard.CategoryLimits>,
        val sourceOptions: List<com.zyke.message.domain.dashboard.SourceOption>,
        val forecast: com.zyke.message.domain.forecast.SpendForecast?
    )

    private companion object {
        const val SLOW_REBUILD_MS = 150L
        const val SLOWEST_PARTS_LOGGED = 5
        const val MAX_QUERIES_KEPT = 8
    }

    class Factory(
        private val observeMessages: ObserveMessagesUseCase,
        private val budgetRepository: BudgetRepository,
        private val cardRepository: CardRepository = NoCards,
        private val userStateRepository: UserMessageStateRepository? = null,
        private val planRepository: MoneyPlanRepository = NoMoneyPlan,
        private val hiddenAccounts: com.zyke.message.domain.repository.HiddenAccountRepository = com.zyke.message.domain.repository.NoHiddenAccounts,
        private val pfAccounts: com.zyke.message.domain.repository.ProvidentFundAccountRepository = com.zyke.message.domain.repository.NoProvidentFundAccounts,
        private val insightsFilter: com.zyke.message.domain.repository.InsightsFilterRepository = com.zyke.message.domain.repository.NoInsightsFilter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            DashboardViewModel(
                observeMessages, budgetRepository, cardRepository = cardRepository, userStateRepository = userStateRepository,
                planRepository = planRepository, hiddenAccounts = hiddenAccounts, pfAccounts = pfAccounts,
                insightsFilter = insightsFilter
            ) as T
    }
}
