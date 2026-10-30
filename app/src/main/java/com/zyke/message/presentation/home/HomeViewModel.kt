package com.zyke.message.presentation.home

import com.zyke.message.domain.repository.NoReminders
import com.zyke.message.domain.repository.ReminderRepository
import com.zyke.message.domain.repository.NoGoals
import com.zyke.message.domain.repository.GoalRepository
import com.zyke.message.domain.reminders.Reminders
import com.zyke.message.domain.goals.Goals
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.cycle.CycleRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.feed.AttentionBuilder
import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.budget.DailyBudgets
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.plan.PlanInsightsBuilder
import com.zyke.message.domain.plan.QuickAdd
import com.zyke.message.domain.plan.QuickAdds
import com.zyke.message.domain.repository.CardRepository
import com.zyke.message.domain.repository.MoneyPlanRepository
import com.zyke.message.domain.repository.NoCards
import com.zyke.message.domain.repository.NoMoneyPlan
import com.zyke.message.domain.insights.LowBalance
import com.zyke.message.domain.insights.PaceNudges
import com.zyke.message.domain.insights.RefundTracker
import com.zyke.message.domain.insights.SpendAlerts
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.repository.NoSplits
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.NoNicknames
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
    private val splitRepository: SplitRepository = NoSplits,
    private val nicknames: NicknameRepository = NoNicknames,
    private val planRepository: MoneyPlanRepository = NoMoneyPlan,
    private val cardRepository: CardRepository = NoCards,
    private val goalRepository: GoalRepository = NoGoals,
    private val reminderRepository: ReminderRepository = NoReminders,
    private val hiddenAccounts: com.zyke.message.domain.repository.HiddenAccountRepository = com.zyke.message.domain.repository.NoHiddenAccounts,
    private val pfAccounts: com.zyke.message.domain.repository.ProvidentFundAccountRepository = com.zyke.message.domain.repository.NoProvidentFundAccounts,
    private val calendarEvents: com.zyke.message.domain.repository.CalendarEventRepository = com.zyke.message.domain.repository.NoCalendarEvents
) : MviViewModel<State, Intent, Effect>(
    State(now = clock.instant(), userState = userStateRepository.state.value, budgets = budgetRepository.budgets.value,
        dailyBudgets = budgetRepository.dailyBudgets.value, wishlist = wishlist.items.value, splits = splitRepository.splits.value,
        plan = planRepository.plan.value, cards = cardRepository.cards.value, hiddenAccounts = hiddenAccounts.hidden.value, pfAccountKeys = pfAccounts.keys.value, events = calendarEvents.events.value,
        goalList = goalRepository.goals.value, reminderList = reminderRepository.reminders.value)
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
        viewModelScope.launch { planRepository.plan.collect { plan -> reduceHome { copy(plan = plan) } } }
        viewModelScope.launch { goalRepository.goals.collect { goals -> reduceHome { copy(goalList = goals) } } }
        viewModelScope.launch { reminderRepository.reminders.collect { reminders -> reduceHome { copy(reminderList = reminders) } } }
        viewModelScope.launch { cardRepository.cards.collect { cards -> reduceHome { copy(cards = cards) } } }
        viewModelScope.launch { hiddenAccounts.hidden.collect { keys -> reduceHome { copy(hiddenAccounts = keys) } } }
        viewModelScope.launch { calendarEvents.events.collect { events -> reduceHome { copy(events = events) } } }
        viewModelScope.launch { pfAccounts.keys.collect { keys -> reduceHome { copy(pfAccountKeys = keys) } } }
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
            is Intent.DismissAttention -> userStateRepository.dismissAttention(intent.key, intent.forever)
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
            is Intent.SetMessageCategory -> categoryOverrides.set(CategoryKey.messageKey(intent.messageId), intent.category)
            is Intent.SetCategory -> currentState().messages.firstOrNull { it.id == intent.messageId }
                ?.let(CategoryKey::of)
                ?.let { categoryOverrides.set(it, intent.category) }
            Intent.OpenCalendar -> reduce { copy(calendarOffset = 0) }
            Intent.CloseCalendar -> reduce { copy(calendarOffset = null) }
            is Intent.ShiftCalendar -> reduce { copy(calendarOffset = calendarOffset?.plus(intent.months)?.coerceAtMost(0)) }
            is Intent.SaveGoal -> goalRepository.save(intent.goal)
            is Intent.RemoveGoal -> goalRepository.remove(intent.id)
            is Intent.AddToGoal -> goalRepository.goals.value.firstOrNull { it.id == intent.id }?.let {
                goalRepository.save(it.copy(saved = (it.saved + intent.amount).max(java.math.BigDecimal.ZERO)))
            }
            is Intent.SaveReminder -> reminderRepository.save(intent.reminder)
            is Intent.RemoveReminder -> reminderRepository.remove(intent.id)
            is Intent.OpenAccount -> reduce { copy(openAccountKey = intent.key) }
            Intent.CloseAccount -> reduce { copy(openAccountKey = null) }
            is Intent.OpenJourney -> reduce { copy(journeyMessageId = intent.messageId) }
            Intent.CloseJourney -> reduce { copy(journeyMessageId = null) }
            Intent.OpenAgenda -> reduce { copy(showAgenda = true) }
            Intent.CloseAgenda -> reduce { copy(showAgenda = false) }
            is Intent.SaveEvent -> calendarEvents.save(intent.event)
            is Intent.RemoveEvent -> calendarEvents.remove(intent.id)
            Intent.OpenGoals -> reduce { copy(showGoals = true) }
            Intent.CloseGoals -> reduce { copy(showGoals = false) }
            Intent.OpenReminders -> reduce { copy(showReminders = true) }
            Intent.CloseReminders -> reduce { copy(showReminders = false) }
            Intent.OpenCodes -> reduce { copy(showCodes = true) }
            Intent.CloseCodes -> reduce { copy(showCodes = false) }
            is Intent.NameAccount -> nameAccount(intent.key, intent.name)
            is Intent.SetProvidentFund -> pfAccounts.set(intent.key, intent.on)
            is Intent.SetStatementDay -> setStatementDay(intent.key, intent.day)
            is Intent.RemoveAccount -> {
                hiddenAccounts.hide(intent.key)
                reduce { copy(openAccountKey = null) }
            }
            Intent.OpenQuickAdds -> reduce { copy(showQuickAdds = true) }
            Intent.CloseQuickAdds -> reduce { copy(showQuickAdds = false) }
            is Intent.DismissQuick -> userStateRepository.dismissAttention(intent.key)
            is Intent.AddQuick -> addQuick(intent.quick)
            is Intent.Rename -> currentState().messages.firstOrNull { it.id == intent.messageId }?.let { message ->
                val own = CategoryKey.messageKey(message.id)
                // Naming this one transaction and naming its sender or receiver are two separate things.
                if (intent.onlyThis) nicknames.set(own, intent.name) else CategoryKey.of(message)?.let { nicknames.set(it, intent.name) }
            }
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

    private fun setStatementDay(key: String, day: Int?) {
        val account = currentState().accounts.accounts.firstOrNull { it.key == key } ?: return
        val existing = account.userCard
        cardRepository.save(
            UserCard(
                id = existing?.id ?: "named-$key", type = existing?.type ?: CardType.CREDIT, last4 = account.last4,
                name = existing?.name.orEmpty(), statementDay = day, bankName = existing?.bankName ?: account.bankName.displayString
            )
        )
    }

    private fun nameAccount(key: String, name: String) {
        val account = currentState().accounts.accounts.firstOrNull { it.key == key } ?: return
        val type = when (account.kind) {
            AccountKind.BANK_ACCOUNT -> CardType.ACCOUNT
            AccountKind.CREDIT_CARD -> CardType.CREDIT
            AccountKind.DEBIT_CARD -> CardType.DEBIT
        }
        val existing = account.userCard
        cardRepository.save(
            UserCard(
                id = existing?.id ?: "named-$key", type = existing?.type ?: type, last4 = account.last4,
                name = name.trim(), statementDay = existing?.statementDay, bankName = existing?.bankName ?: account.bankName.displayString
            )
        )
    }

    private fun addQuick(quick: QuickAdd) {
        val plan = planRepository.plan.value
        when (quick) {
            is QuickAdd.Salary -> planRepository.save(plan.copy(salary = quick.amount, salaryDay = quick.day))
            is QuickAdd.Item -> planRepository.save(plan.copy(items = plan.items + quick.item))
            is QuickAdd.Account -> cardRepository.save(quick.card)
            is QuickAdd.SavingsGoal -> planRepository.save(plan.copy(savingsGoal = quick.amount))
            is QuickAdd.Limit -> budgetRepository.setBudget(quick.category, quick.monthly)
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
            val cycle = CycleRule.resolve(updated.plan, updated.messages, updated.now, zone)
            val dashboard = DashboardBuilder.build(updated.messages, DashboardQuery(), updated.now, zone, updated.budgets, cycle)

            val limits = SpendingLimits.build(live, updated.budgets, updated.dailyBudgets, updated.now, zone, cycle)
            val balances = BalanceReader.latest(live, zone)

            val insights = PlanInsightsBuilder.build(updated.plan, live, updated.now, zone, cycle)
            val allowance = DailyBudgets.allowance(insights, today, cycle)

            updated.copy(
                cycle = cycle,
                daily = allowance?.let { DailyBudgets.status(live, it, updated.now, zone, updated.plan.items) },
                goals = Goals.all(updated.goalList, today),
                reminders = Reminders.upcoming(updated.reminderList, today),
                providentFund = com.zyke.message.domain.pf.ProvidentFunds.find(live, zone),
                accounts = Accounts.build(live, updated.cards, updated.now, zone, cycle, updated.hiddenAccounts, updated.pfAccountKeys),
                quickAdds = QuickAdds.find(live, updated.plan, updated.cards, updated.userState.dismissedAttention, updated.now, zone, updated.budgets),
                nudges = (PaceNudges.build(limits, updated.wishlist, updated.now, zone, cycle) + LowBalance.warnings(live, balances, today))
                    .take(MAX_NUDGES),
                refunds = RefundTracker.build(live, updated.now, zone),
                owed = SplitInsights.build(updated.splits, updated.messages, zone).filter { !it.settled },
                subscriptions = Subscriptions.find(live, updated.now, zone),
                attention = AttentionBuilder.build(
                    live.filter { !it.isPromotional }, updated.userState.dismissedAttention, updated.now, zone, updated.userState.dismissedAt
                ),
                thisMonth = dashboard,
                upcoming = UpcomingBuilder.build(updated.messages, today, includePaidToday = true, zone = zone),
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
        private val splits: SplitRepository = NoSplits,
        private val nicknames: NicknameRepository = NoNicknames,
        private val planRepository: MoneyPlanRepository = NoMoneyPlan,
        private val cardRepository: CardRepository = NoCards,
        private val goalRepository: GoalRepository = NoGoals,
        private val reminderRepository: ReminderRepository = NoReminders,
        private val hiddenAccounts: com.zyke.message.domain.repository.HiddenAccountRepository = com.zyke.message.domain.repository.NoHiddenAccounts,
        private val pfAccounts: com.zyke.message.domain.repository.ProvidentFundAccountRepository = com.zyke.message.domain.repository.NoProvidentFundAccounts,
        private val calendarEvents: com.zyke.message.domain.repository.CalendarEventRepository = com.zyke.message.domain.repository.NoCalendarEvents
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            HomeViewModel(
                observeMessages, userStateRepository, budgetRepository,
                categoryOverrides = categoryOverrides, mutedSenders = mutedSenders, wishlist = wishlist, splitRepository = splits, nicknames = nicknames,
                planRepository = planRepository, cardRepository = cardRepository,
                goalRepository = goalRepository, reminderRepository = reminderRepository, hiddenAccounts = hiddenAccounts, pfAccounts = pfAccounts, calendarEvents = calendarEvents
            ) as T
    }

    private companion object {
        const val RECENT_COUNT = 5
        const val MAX_NUDGES = 4
    }
}
