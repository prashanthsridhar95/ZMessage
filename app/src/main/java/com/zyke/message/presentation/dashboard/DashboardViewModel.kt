package com.zyke.message.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.dashboard.DashboardContract.Effect
import com.zyke.message.presentation.dashboard.DashboardContract.Intent
import com.zyke.message.presentation.dashboard.DashboardContract.State
import com.zyke.message.presentation.mvi.MviViewModel
import java.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val observeMessages: ObserveMessagesUseCase,
    private val clock: Clock = Clock.systemDefaultZone()
) : MviViewModel<State, Intent, Effect>(State(now = clock.instant())) {

    private var observeJob: Job? = null

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted ->
                if (intent.permissionGranted) startObserving() else reduce { copy(isLoading = false, permissionMissing = true) }

            is Intent.SelectPeriod -> reduceDashboard { copy(period = intent.period, expandedVendor = null) }
            is Intent.SelectDirection -> reduceDashboard { copy(direction = intent.direction, expandedVendor = null) }
            is Intent.SelectCategory -> reduceDashboard {
                copy(category = if (category == intent.category) null else intent.category, expandedVendor = null)
            }
            Intent.ClearCategory -> reduceDashboard { copy(category = null, expandedVendor = null) }

            is Intent.ToggleVendor -> reduce {
                copy(expandedVendor = if (expandedVendor == intent.key) null else intent.key)
            }
            Intent.ToggleShowAllVendors -> reduce { copy(showAllVendors = !showAllVendors) }
        }
    }

    private fun startObserving() {
        reduce { copy(permissionMissing = false) }
        if (observeJob?.isActive == true) return

        observeJob = viewModelScope.launch {
            observeMessages()
                .catch { reduce { copy(isLoading = false, hasError = true) } }
                .collect { inbox ->
                    reduceDashboard { copy(isLoading = false, hasError = false, messages = inbox, now = clock.instant()) }
                }
        }
    }

    private fun reduceDashboard(change: State.() -> State) = reduce {
        val updated = change()
        updated.copy(
            dashboard = DashboardBuilder.build(updated.messages, updated.query, updated.now, clock.zone)
        )
    }

    class Factory(private val observeMessages: ObserveMessagesUseCase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            DashboardViewModel(observeMessages) as T
    }
}
