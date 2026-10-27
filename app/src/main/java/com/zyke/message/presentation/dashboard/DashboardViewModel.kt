package com.zyke.message.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.dashboard.CardBillsBuilder
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.ReportCardBuilder
import com.zyke.message.domain.repository.BudgetRepository
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
    private val clock: Clock = Clock.systemDefaultZone()
) : MviViewModel<State, Intent, Effect>(State(now = clock.instant(), budgets = budgetRepository.budgets.value)) {

    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            budgetRepository.budgets.collect { budgets -> reduceDashboard { copy(budgets = budgets) } }
        }
    }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted ->
                if (intent.permissionGranted) startObserving() else reduce { copy(isLoading = false, permissionMissing = true) }

            is Intent.SelectPeriod -> reduceDashboard { copy(period = intent.period, expandedVendor = null) }
            is Intent.SelectDirection -> reduceDashboard { copy(direction = intent.direction, expandedVendor = null) }
            is Intent.SelectCategory -> reduceDashboard {
                copy(category = if (category == intent.category) null else intent.category, expandedVendor = null)
            }
            is Intent.SetCategory -> reduceDashboard { copy(category = intent.category, expandedVendor = null) }
            Intent.OpenReportCard -> reduce { copy(showReportCard = true) }
            Intent.CloseReportCard -> reduce { copy(showReportCard = false) }
            Intent.OpenFilters -> reduce { copy(showFilterSheet = true) }
            Intent.CloseFilters -> reduce { copy(showFilterSheet = false) }
            Intent.ResetFilters -> reduceDashboard {
                copy(period = DashboardPeriod.THIS_MONTH, category = null, expandedVendor = null)
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
            is Intent.ToggleCard -> reduce { copy(expandedCard = if (expandedCard == intent.key) null else intent.key) }
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
            observeMessages()
                .catch { reduce { copy(isLoading = false, hasError = true) } }
                .collect { inbox ->
                    reduceDashboard { copy(isLoading = false, hasError = false, hasLoaded = true, messages = inbox, now = clock.instant()) }
                }
        }
    }

    private fun reduceDashboard(change: State.() -> State) = reduce {
        val updated = change()
        if (!updated.hasLoaded) {
            updated
        } else {
            updated.copy(
                dashboard = DashboardBuilder.build(updated.messages, updated.query, updated.now, clock.zone, updated.budgets),
                reportCard = ReportCardBuilder.build(updated.messages, updated.budgets, updated.now, clock.zone),
                cardBills = CardBillsBuilder.build(updated.messages, LocalDate.ofInstant(updated.now, clock.zone))
            )
        }
    }

    class Factory(
        private val observeMessages: ObserveMessagesUseCase,
        private val budgetRepository: BudgetRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            DashboardViewModel(observeMessages, budgetRepository) as T
    }
}
