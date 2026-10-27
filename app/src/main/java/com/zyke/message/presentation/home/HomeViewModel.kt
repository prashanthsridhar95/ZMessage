package com.zyke.message.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.feed.AttentionBuilder
import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.home.HomeContract.Effect
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.mvi.MviViewModel
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** The front page: what needs attention, what is coming up, and how the month is going. */
class HomeViewModel(
    private val observeMessages: ObserveMessagesUseCase,
    private val userStateRepository: UserMessageStateRepository,
    private val budgetRepository: BudgetRepository,
    private val clock: Clock = Clock.systemDefaultZone()
) : MviViewModel<State, Intent, Effect>(
    State(now = clock.instant(), userState = userStateRepository.state.value, budgets = budgetRepository.budgets.value)
) {

    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            userStateRepository.state.collect { userState -> reduceHome { copy(userState = userState) } }
        }
        viewModelScope.launch {
            budgetRepository.budgets.collect { budgets -> reduceHome { copy(budgets = budgets) } }
        }
    }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted ->
                if (intent.permissionGranted) startObserving() else reduce { copy(isLoading = false, permissionMissing = true) }
            is Intent.CopyOtp -> {
                userStateRepository.markRead(intent.messageId)
                emitEffect(Effect.CopyToClipboard("One-time Password", intent.code))
            }
            is Intent.DismissAttention -> userStateRepository.dismissAttention(intent.key)
            is Intent.OpenLink -> emitEffect(Effect.OpenLink(intent.url))
            Intent.Retry -> {
                reduce { copy(hasError = false, isLoading = true) }
                startObserving()
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
                    reduceHome {
                        copy(isLoading = false, hasError = false, hasLoaded = true, messages = inbox, now = clock.instant())
                    }
                }
        }
    }

    private fun reduceHome(change: State.() -> State) = reduce {
        val updated = change()
        if (!updated.hasLoaded) {
            updated
        } else {
            val zone = clock.zone
            val today = LocalDate.ofInstant(updated.now, zone)
            val live = updated.messages.filter { it.id !in updated.userState.archivedIds }
            val dashboard = DashboardBuilder.build(updated.messages, DashboardQuery(), updated.now, zone, updated.budgets)

            updated.copy(
                attention = AttentionBuilder.build(
                    live.filter { !it.isPromotional }, updated.userState.dismissedAttention, updated.now, zone
                ),
                thisMonth = dashboard,
                upcoming = UpcomingBuilder.build(updated.messages, today),
                budgetStatus = dashboard.categories
                    .filter { it.budget != null }
                    .sortedByDescending { it.usedFraction ?: 0.0 },
                recent = live.filter { it.content is MessageContent.Transaction }.take(RECENT_COUNT),
                unreadCount = live.count { !it.message.isRead && it.id !in updated.userState.readIds }
            )
        }
    }

    class Factory(
        private val observeMessages: ObserveMessagesUseCase,
        private val userStateRepository: UserMessageStateRepository,
        private val budgetRepository: BudgetRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            HomeViewModel(observeMessages, userStateRepository, budgetRepository) as T
    }

    private companion object {
        const val RECENT_COUNT = 5
    }
}
