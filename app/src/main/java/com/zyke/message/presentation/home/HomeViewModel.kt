package com.zyke.message.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.feed.AttentionBuilder
import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.insights.LowBalance
import com.zyke.message.domain.insights.PaceNudges
import com.zyke.message.domain.insights.RefundTracker
import com.zyke.message.domain.insights.SpendAlerts
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.repository.NoSplits
import com.zyke.message.domain.repository.NoWishlist
import com.zyke.message.domain.repository.SplitRepository
import com.zyke.message.domain.repository.WishlistRepository
import com.zyke.message.domain.split.SplitInsights
import com.zyke.message.domain.life.LifeAgenda
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.MutedSenderRepository
import com.zyke.message.domain.repository.NoMutedSenders
import com.zyke.message.domain.repository.NoCategoryOverrides
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
    private val clock: Clock = Clock.systemDefaultZone(),
    private val categoryOverrides: CategoryOverrideRepository = NoCategoryOverrides,
    private val mutedSenders: MutedSenderRepository = NoMutedSenders,
    private val wishlist: WishlistRepository = NoWishlist,
    private val splitRepository: SplitRepository = NoSplits
) : MviViewModel<State, Intent, Effect>(
    State(now = clock.instant(), userState = userStateRepository.state.value, budgets = budgetRepository.budgets.value,
        dailyBudgets = budgetRepository.dailyBudgets.value, wishlist = wishlist.items.value, splits = splitRepository.splits.value)
) {

    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            userStateRepository.state.collect { userState -> reduceHome { copy(userState = userState) } }
        }
        viewModelScope.launch {
            budgetRepository.budgets.collect { budgets -> reduceHome { copy(budgets = budgets) } }
        }
        viewModelScope.launch {
            budgetRepository.dailyBudgets.collect { daily -> reduceHome { copy(dailyBudgets = daily) } }
        }
        viewModelScope.launch { wishlist.items.collect { items -> reduceHome { copy(wishlist = items) } } }
        viewModelScope.launch { splitRepository.splits.collect { splits -> reduceHome { copy(splits = splits) } } }
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
            is Intent.OpenMessage -> {
                userStateRepository.markRead(intent.messageId)
                reduce { copy(detailMessageId = intent.messageId) }
            }
            Intent.CloseMessage -> reduce { copy(detailMessageId = null) }
            is Intent.OpenBudget -> reduce { copy(budgetDetail = intent.category) }
            Intent.CloseBudget -> reduce { copy(budgetDetail = null) }
            is Intent.TogglePin -> userStateRepository.setPinned(
                intent.messageId,
                intent.messageId !in currentState().userState.pinnedIds
            )
            is Intent.Archive -> {
                reduce { copy(detailMessageId = null) }
                userStateRepository.setArchived(intent.messageId, true)
            }
            is Intent.Unarchive -> userStateRepository.setArchived(intent.messageId, false)
            is Intent.ToggleMute -> currentState().messages.firstOrNull { it.id == intent.messageId }?.let { message ->
                mutedSenders.setMuted(SenderKey.of(message.message.sender), !message.isMuted)
            }
            is Intent.SetCategory -> currentState().messages.firstOrNull { it.id == intent.messageId }
                ?.let(CategoryKey::of)
                ?.let { categoryOverrides.set(it, intent.category) }
            is Intent.StartSplit -> reduce { copy(detailMessageId = null, splittingMessageId = intent.messageId) }
            Intent.CancelSplit -> reduce { copy(splittingMessageId = null) }
            is Intent.SaveSplit -> {
                splitRepository.save(intent.split)
                reduce { copy(splittingMessageId = null) }
            }
            is Intent.RemoveSplit -> splitRepository.remove(intent.id)
            is Intent.SettleShare -> splitRepository.splits.value.firstOrNull { it.id == intent.splitId }?.let { split ->
                splitRepository.save(
                    split.copy(people = split.people.map { if (it.name == intent.person) it.copy(settledByHand = true) else it })
                )
            }
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

            val limits = SpendingLimits.build(live, updated.budgets, updated.dailyBudgets, updated.now, zone)
            val balances = BalanceReader.latest(live, zone)

            updated.copy(
                nudges = (PaceNudges.build(limits, updated.wishlist, updated.now, zone) + LowBalance.warnings(live, balances, today))
                    .take(MAX_NUDGES),
                refunds = RefundTracker.build(live, updated.now, zone),
                owed = SplitInsights.build(updated.splits, updated.messages, zone).filter { !it.settled },
                subscriptions = Subscriptions.find(live, updated.now, zone),
                attention = AttentionBuilder.build(
                    live.filter { !it.isPromotional }, updated.userState.dismissedAttention, updated.now, zone
                ),
                thisMonth = dashboard,
                upcoming = UpcomingBuilder.build(updated.messages, today),
                budgetStatus = dashboard.categories
                    .filter { it.budget != null }
                    .sortedByDescending { it.usedFraction ?: 0.0 },
                recent = live.filter { it.content is MessageContent.Transaction }.take(RECENT_COUNT),
                alerts = SpendAlerts.find(live, updated.now.toEpochMilli()),
                agenda = LifeAgenda.build(live.filter { !it.isPromotional }, today, zone),
                unreadCount = live.count { !it.message.isRead && it.id !in updated.userState.readIds }
            )
        }
    }

    class Factory(
        private val observeMessages: ObserveMessagesUseCase,
        private val userStateRepository: UserMessageStateRepository,
        private val budgetRepository: BudgetRepository,
        private val categoryOverrides: CategoryOverrideRepository = NoCategoryOverrides,
        private val mutedSenders: MutedSenderRepository = NoMutedSenders,
        private val wishlist: WishlistRepository = NoWishlist,
        private val splits: SplitRepository = NoSplits
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            HomeViewModel(
                observeMessages, userStateRepository, budgetRepository,
                categoryOverrides = categoryOverrides, mutedSenders = mutedSenders, wishlist = wishlist, splitRepository = splits
            ) as T
    }

    private companion object {
        const val RECENT_COUNT = 5
        const val MAX_NUDGES = 4
    }
}
