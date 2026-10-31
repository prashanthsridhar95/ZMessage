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
    private val insightsFilter: com.zyke.message.domain.repository.InsightsFilterRepository = com.zyke.message.domain.repository.NoInsightsFilter
) : MviViewModel<State, Intent, Effect>(
    insightsFilter.load().let { saved ->
        State(
            now = clock.instant(), budgets = budgetRepository.budgets.value, cards = cardRepository.cards.value, plan = planRepository.plan.value,
            period = saved.period, customRange = saved.customRange, source = saved.source
        )
    }
) {

    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            budgetRepository.budgets.collect { budgets -> reduceDashboard { copy(budgets = budgets) } }
        }
        viewModelScope.launch {
            planRepository.plan.collect { plan -> reduceDashboard { copy(plan = plan) } }
        }
        viewModelScope.launch {
            budgetRepository.dailyBudgets.collect { daily -> reduceDashboard { copy(dailyBudgets = daily) } }
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
            is Intent.SelectDirection -> reduceDashboard { copy(direction = intent.direction, expandedVendor = null) }
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
        insightsFilter.save(
            com.zyke.message.domain.repository.SavedInsightsFilter(
                period = if (now.cycleOffset != 0) DashboardPeriod.THIS_MONTH else now.period,
                customRange = now.customRange.takeIf { now.period == DashboardPeriod.CUSTOM && now.cycleOffset == 0 },
                source = now.source
            )
        )
    }

    private fun archivedIds(): Set<Long> = userStateRepository?.state?.value?.archivedIds.orEmpty()

    private fun reduceDashboard(change: State.() -> State) = reduce {
        val updated = change()
        if (!updated.hasLoaded) {
            updated
        } else {
            val flowQuery = updated.query.copy(category = null)
            val cycle = CycleRule.resolve(updated.plan, updated.messages, updated.now, clock.zone)
            // The figures and charts follow the chosen source (UPI, one account or one card); the rest of the screen does not.
            val scoped = com.zyke.message.domain.dashboard.SourceFilters.apply(updated.messages, updated.source)
            val planInsights = PlanInsightsBuilder.build(updated.plan, updated.messages, updated.now, clock.zone, cycle)
            updated.copy(
                cycle = cycle,
                categoryDetail = updated.openCategory?.let { open ->
                    DashboardBuilder.build(scoped, updated.query.copy(category = open), updated.now, clock.zone, updated.budgets, cycle)
                },
                dashboard = DashboardBuilder.build(scoped, updated.query, updated.now, clock.zone, updated.budgets, cycle),
                expenseTrend = DashboardBuilder.build(
                    scoped, flowQuery.copy(direction = com.zyke.message.domain.dashboard.FlowDirection.SPENT),
                    updated.now, clock.zone, updated.budgets, cycle
                ).trend,
                incomeTrend = DashboardBuilder.build(
                    scoped, flowQuery.copy(direction = com.zyke.message.domain.dashboard.FlowDirection.RECEIVED),
                    updated.now, clock.zone, updated.budgets, cycle
                ).trend,
                reportCard = ReportCardBuilder.build(scoped, updated.budgets, updated.now, clock.zone, cycle),
                cardBills = CardBillsBuilder.build(updated.messages, LocalDate.ofInstant(updated.now, clock.zone)),
                cardCycles = CardCycleBuilder.build(updated.messages, updated.cards, updated.now, clock.zone),
                limits = SpendingLimits.build(updated.messages, updated.budgets, updated.dailyBudgets, updated.now, clock.zone, cycle),
                sourceOptions = com.zyke.message.domain.dashboard.SourceFilters.options(updated.messages),
                planInsights = planInsights,
                limitSuggestions = com.zyke.message.domain.forecast.LimitSuggestions.build(
                    updated.messages, updated.budgets, planInsights?.spendable, updated.now, clock.zone, cycle
                ),
                forecast = com.zyke.message.domain.forecast.SpendForecasts.build(
                    updated.messages,
                    // What the bank accounts hold, apart from any provident fund and anything removed as not theirs.
                    com.zyke.message.domain.accounts.Accounts.build(
                        updated.messages.filter { it.id !in archivedIds() }, updated.cards, updated.now, clock.zone, cycle,
                        hiddenAccounts.hidden.value, pfAccounts.keys.value
                    ).totalBalance,
                    updated.now, clock.zone, cycle
                )
            )
        }
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
