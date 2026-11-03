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
import com.zyke.message.domain.plan.PlanActions
import com.zyke.message.domain.plan.PlanKeys
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private val calendarEvents: com.zyke.message.domain.repository.CalendarEventRepository = com.zyke.message.domain.repository.NoCalendarEvents,
    private val setAsideRepository: com.zyke.message.domain.ledger.SetAsideRepository = com.zyke.message.domain.ledger.NoSetAsides,
    private val merges: com.zyke.message.domain.repository.MergeRepository = com.zyke.message.domain.repository.NoMerges,
    private val manualEntryRepository: com.zyke.message.domain.ledger.ManualEntryRepository = com.zyke.message.domain.ledger.NoManualEntries,
    private val recurring: com.zyke.message.domain.repository.RecurringConfirmationRepository = com.zyke.message.domain.repository.NoRecurringConfirmations,
    private val loanRepository: com.zyke.message.domain.repository.LoanRepository = com.zyke.message.domain.repository.NoLoans,
    private val planActionRepository: com.zyke.message.domain.repository.PlanActionRepository = com.zyke.message.domain.repository.NoPlanActions,
    private val dailyOverride: com.zyke.message.domain.budget.DailyBudgetOverrideRepository = com.zyke.message.domain.budget.NoDailyBudgetOverride,
    private val cancelledSubscriptions: com.zyke.message.domain.repository.CancelledSubscriptionRepository = com.zyke.message.domain.repository.NoCancelledSubscriptions,
    private val accountOverrides: com.zyke.message.domain.accounts.AccountOverrideRepository = com.zyke.message.domain.accounts.NoAccountOverrides,
    /** Where the derived parts of the state are worked out; never the main thread outside tests. */
    private val deriveContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default
) : MviViewModel<State, Intent, Effect>(
    State(now = clock.instant(), userState = userStateRepository.state.value, budgets = budgetRepository.budgets.value,
        dailyBudgets = budgetRepository.dailyBudgets.value, wishlist = wishlist.items.value, splits = splitRepository.splits.value,
        plan = planRepository.plan.value, cards = cardRepository.cards.value, hiddenAccounts = hiddenAccounts.hidden.value, accountOverrides = accountOverrides.overrides.value, pfAccountKeys = pfAccounts.keys.value, events = calendarEvents.events.value,
        goalList = goalRepository.goals.value, reminderList = reminderRepository.reminders.value, loanList = loanRepository.loans.value, asides = setAsideRepository.state.value,
        planActions = planActionRepository.state.value, dailyOverride = dailyOverride.amount.value,
        cancelledSubs = cancelledSubscriptions.cancelled.value)
) {

    // Declared before init: the first collections below can run at once and reach the memo.
    private val memos = com.zyke.message.presentation.mvi.PartMemo()

    private var observeJob: Job? = null

    /** Writes the person's choices to disk one after another, away from the main thread. */
    private val writes = kotlinx.coroutines.channels.Channel<() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    private fun io(write: () -> Unit) {
        writes.trySend(write)
    }

    init {
        startDeriving("home", deriveContext) { snapshot -> derive(snapshot) }
        viewModelScope.launch(deriveContext) {
            for (write in writes) {
                runCatching { write() }.onFailure { com.zyke.message.data.diag.DevLog.e("write", "a saved choice could not be written", it) }
            }
        }
        viewModelScope.launch {
            userStateRepository.state.collect { userState -> reduceHome { copy(userState = userState) } }
        }
        viewModelScope.launch {
            budgetRepository.budgets.collect { budgets -> reduceHome { copy(budgets = budgets) } }
        }
        viewModelScope.launch {
            budgetRepository.dailyBudgets.collect { daily -> reduceHome { copy(dailyBudgets = daily) } }
        }
        viewModelScope.launch { dailyOverride.amount.collect { amount -> reduceHome { copy(dailyOverride = amount) } } }
        viewModelScope.launch {
            budgetRepository.adjustments.collect { adjustments -> reduceHome { copy(budgetAdjustments = adjustments) } }
        }
        viewModelScope.launch { planRepository.plan.collect { plan -> reduceHome { copy(plan = plan) } } }
        viewModelScope.launch { goalRepository.goals.collect { goals -> reduceHome { copy(goalList = goals) } } }
        viewModelScope.launch { reminderRepository.reminders.collect { reminders -> reduceHome { copy(reminderList = reminders) } } }
        viewModelScope.launch { planActionRepository.state.collect { hides -> reduceHome { copy(planActions = hides) } } }
        io { planActionRepository.clean(LocalDate.now(clock)) }
        viewModelScope.launch { loanRepository.loans.collect { loans -> reduceHome { copy(loanList = loans) } } }
        viewModelScope.launch { cardRepository.cards.collect { cards -> reduceHome { copy(cards = cards) } } }
        viewModelScope.launch { hiddenAccounts.hidden.collect { keys -> reduceHome { copy(hiddenAccounts = keys) } } }
        viewModelScope.launch { accountOverrides.overrides.collect { chosen -> reduceHome { copy(accountOverrides = chosen) } } }
        viewModelScope.launch { recurring.states.collect { states -> reduce { copy(recurringStates = states) } } }
        viewModelScope.launch { cancelledSubscriptions.cancelled.collect { cancelled -> reduceHome { copy(cancelledSubs = cancelled) } } }
        viewModelScope.launch { calendarEvents.events.collect { events -> reduceHome { copy(events = events) } } }
        viewModelScope.launch { pfAccounts.keys.collect { keys -> reduceHome { copy(pfAccountKeys = keys) } } }
        viewModelScope.launch { wishlist.items.collect { items -> reduceHome { copy(wishlist = items) } } }
        viewModelScope.launch { setAsideRepository.state.collect { asides -> reduceHome { copy(asides = asides) } } }
        viewModelScope.launch { splitRepository.splits.collect { splits -> reduceHome { copy(splits = splits) } } }
    }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted ->
                if (intent.permissionGranted) startObserving() else reduce { copy(isLoading = false, permissionMissing = true) }
            is Intent.CopyOtp -> {
                io { userStateRepository.markRead(intent.messageId) }
                emitEffect(Effect.CopyToClipboard("One-time Password", intent.code))
            }
            is Intent.DismissAttention -> io { userStateRepository.dismissAttention(intent.key, intent.forever) }
            is Intent.RestoreAttention -> io { userStateRepository.restoreAttention(intent.key) }
            is Intent.LinkTransfer -> io { setAsideRepository.link(intent.debitId, intent.creditId) }
            is Intent.DismissTransfer -> io { setAsideRepository.dismissSuggestion(intent.key) }
            is Intent.MergeDuplicates -> io { merges.merge(intent.primaryId, intent.duplicateId) }
            is Intent.UnmergeDuplicate -> io { merges.unmerge(intent.duplicateId) }
            is Intent.SaveManualEntry -> io { manualEntryRepository.save(intent.entry.let { if (it.id <= 0) it.copy(id = manualEntryRepository.nextId()) else it }) }
            is Intent.OpenLink -> emitEffect(Effect.OpenLink(intent.url))
            is Intent.OpenMessage -> {
                io { userStateRepository.markRead(intent.messageId) }
                reduce { copy(detailMessageId = intent.messageId) }
            }
            Intent.CloseMessage -> reduce { copy(detailMessageId = null) }
            is Intent.OpenBudget -> reduce { copy(budgetDetail = intent.category) }
            Intent.CloseBudget -> reduce { copy(budgetDetail = null) }
            is Intent.TogglePin -> {
                val pin = intent.messageId !in currentState().userState.pinnedIds
                io { userStateRepository.setPinned(intent.messageId, pin) }
            }
            is Intent.Archive -> {
                reduce { copy(detailMessageId = null) }
                io { userStateRepository.setArchived(intent.messageId, true) }
            }
            is Intent.Unarchive -> io { userStateRepository.setArchived(intent.messageId, false) }
            is Intent.ToggleMute -> currentState().messages.firstOrNull { it.id == intent.messageId }?.let { message ->
                val sender = SenderKey.of(message.message.sender)
                val mute = !message.isMuted
                io { mutedSenders.setMuted(sender, mute) }
            }
            is Intent.SetMessageCategory -> {
                com.zyke.message.data.diag.DevLog.d("category") { "home: this transaction id=${intent.messageId} -> ${intent.category}" }
                io { categoryOverrides.set(CategoryKey.messageKey(intent.messageId), intent.category) }
            }
            is Intent.SetCategory -> {
                val message = currentState().messages.firstOrNull { it.id == intent.messageId }
                val key = message?.let(CategoryKey::of)
                com.zyke.message.data.diag.DevLog.d("category") {
                    "home: sender/receiver id=${intent.messageId} -> ${intent.category} messageFound=${message != null} key=${com.zyke.message.domain.diag.DiagLog.key(key)} messagesInState=${currentState().messages.size}"
                }
                key?.let { io { categoryOverrides.set(it, intent.category) } }
            }
            Intent.OpenCalendar -> reduce { copy(calendarOffset = 0) }
            Intent.OpenAdjustDaily -> reduce { copy(adjustingDaily = true) }
            Intent.CloseAdjustDaily -> reduce { copy(adjustingDaily = false) }
            is Intent.SetDailyBudget -> {
                val amount = intent.amount.takeIf { it.signum() > 0 }
                io { dailyOverride.set(amount) }
                reduce { copy(adjustingDaily = false) }
            }
            Intent.ResetDailyBudget -> {
                io { dailyOverride.set(null) }
                reduce { copy(adjustingDaily = false) }
            }
            Intent.CloseCalendar -> reduce { copy(calendarOffset = null) }
            is Intent.ShiftCalendar -> reduce { copy(calendarOffset = calendarOffset?.plus(intent.months)?.coerceAtMost(0)) }
            is Intent.SaveGoal -> goalRepository.save(intent.goal)
            is Intent.RemoveGoal -> goalRepository.remove(intent.id)
            is Intent.AddToGoal -> goalRepository.goals.value.firstOrNull { it.id == intent.id }?.let {
                goalRepository.save(it.copy(saved = (it.saved + intent.amount).max(java.math.BigDecimal.ZERO)))
            }
            is Intent.SaveReminder -> reminderRepository.save(intent.reminder)
            is Intent.RemoveReminder -> reminderRepository.remove(intent.id)
            is Intent.PlanDismiss -> io { planActionRepository.dismiss(intent.key, intent.label, intent.kind) }
            is Intent.PlanSnooze -> io { planActionRepository.snooze(intent.key, intent.label, intent.kind, intent.until, LocalDate.now(clock)) }
            is Intent.PlanRestore -> io { planActionRepository.restore(intent.key) }
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
            Intent.OpenSubscriptions -> reduce { copy(showSubscriptions = true) }
            Intent.CloseSubscriptions -> reduce { copy(showSubscriptions = false) }
            is Intent.CancelSubscription -> cancelledSubscriptions.markCancelled(intent.key, intent.on)
            is Intent.RestoreSubscription -> cancelledSubscriptions.clear(intent.key)
            Intent.OpenReminders -> reduce { copy(showReminders = true) }
            Intent.CloseReminders -> reduce { copy(showReminders = false) }
            Intent.OpenCodes -> reduce { copy(showCodes = true) }
            Intent.CloseCodes -> reduce { copy(showCodes = false) }
            is Intent.NameAccount -> nameAccount(intent.key, intent.name)
            is Intent.EditAccount -> editAccount(intent)
            is Intent.SetProvidentFund -> pfAccounts.set(intent.key, intent.on)
            is Intent.SetStatementDay -> setStatementDay(intent.key, intent.day)
            is Intent.RemoveAccount -> {
                hiddenAccounts.hide(intent.key)
                reduce { copy(openAccountKey = null) }
            }
            is Intent.ConfirmRecurring -> recurring.set(intent.key, com.zyke.message.domain.repository.RecurringState.CONFIRMED)
            is Intent.IgnoreRecurring -> recurring.set(intent.key, com.zyke.message.domain.repository.RecurringState.IGNORED)
            Intent.OpenQuickAdds -> reduce { copy(showQuickAdds = true) }
            Intent.CloseQuickAdds -> reduce { copy(showQuickAdds = false) }
            is Intent.DismissQuick -> io { userStateRepository.dismissAttention(intent.key) }
            is Intent.AddQuick -> addQuick(intent.quick)
            is Intent.Rename -> {
                val message = currentState().messages.firstOrNull { it.id == intent.messageId }
                com.zyke.message.data.diag.DevLog.d("nickname") {
                    "home: rename id=${intent.messageId} onlyThis=${intent.onlyThis} nameLength=${intent.name.length} messageFound=${message != null} key=${com.zyke.message.domain.diag.DiagLog.key(message?.let(CategoryKey::of))} messagesInState=${currentState().messages.size}"
                }
                message?.let {
                    val own = CategoryKey.messageKey(it.id)
                    // Naming this one transaction and naming its sender or receiver are two separate things.
                    if (intent.onlyThis) io { nicknames.set(own, intent.name) } else CategoryKey.of(it)?.let { key -> io { nicknames.set(key, intent.name) } }
                }
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
            (existing ?: UserCard(id = "named-$key", type = CardType.CREDIT, last4 = account.last4, name = ""))
                .copy(statementDay = day)
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
            (existing ?: UserCard(id = "named-$key", type = type, last4 = account.last4, name = ""))
                .copy(name = name.trim())
        )
    }

    /**
     * The Edit sheet: a nickname, the bank and the type. The bank and type go to the account's override (keyed by the
     * account, so new messages keep it); the nickname and the bank also go to its card entry, so the card form shows them.
     */
    private fun editAccount(edit: Intent.EditAccount) {
        val account = currentState().accounts.accounts.firstOrNull { it.key == edit.key } ?: return
        val typed = edit.bank.trim()
        val sameAsDetected = typed.isEmpty() || (account.detectedBank != com.zyke.message.domain.model.BankName.OTHERS &&
            com.zyke.message.domain.cards.CardCatalog.bank(typed) == account.detectedBank)
        val bank = if (sameAsDetected) "" else typed
        val kind = edit.kind?.takeIf { it != account.detectedKind }
        // A balance typed before and left as it is keeps its time; a new or changed one is as of now.
        val before = accountOverrides.overrides.value[edit.key]?.manual
        val manual = when {
            edit.balance == null -> null
            before != null && before.amount.compareTo(edit.balance) == 0 -> before
            else -> com.zyke.message.domain.accounts.ManualBalance(edit.key, edit.balance, System.currentTimeMillis())
        }
        val chosen = com.zyke.message.domain.accounts.AccountOverride(bank = bank, kind = kind, manual = manual)
        if (chosen.isEmpty) accountOverrides.clear(edit.key) else accountOverrides.set(edit.key, chosen)
        com.zyke.message.data.diag.DevLog.d("accounts") {
            "edit account key=${com.zyke.message.domain.diag.DiagLog.key(edit.key)} bankChosen=${bank.isNotEmpty()} kindChosen=${kind != null} balanceSet=${manual != null} nameLength=${edit.name.trim().length}"
        }
        val existing = account.userCard
        if (existing != null || edit.name.isNotBlank()) {
            val type = when (account.kind) {
                AccountKind.BANK_ACCOUNT -> CardType.ACCOUNT
                AccountKind.CREDIT_CARD -> CardType.CREDIT
                AccountKind.DEBIT_CARD -> CardType.DEBIT
            }
            cardRepository.save(
                (existing ?: UserCard(id = "named-${edit.key}", type = type, last4 = account.last4, name = ""))
                    .copy(name = edit.name.trim(), bankName = bank)
            )
        }
    }

    private fun addQuick(quick: QuickAdd) {
        val plan = planRepository.plan.value
        when (quick) {
            is QuickAdd.Salary -> planRepository.save(plan.copy(salary = quick.amount, salaryDay = quick.day, salaryOnLastWorkingDay = false))
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
            observeMessages.calm { com.zyke.message.data.perf.LiteRuntime.calmGapMs() }
                .catch { reduce { copy(isLoading = false, hasError = true) } }
                .collect { inbox ->
                    reduceHome {
                        copy(isLoading = false, hasError = false, hasLoaded = true, messages = inbox, now = clock.instant())
                    }
                }
        }
    }

    /** Applies the change at once; the derived parts follow from the worker thread, so the main thread never waits for them. */
    private fun reduceHome(change: State.() -> State) {
        reduce(change)
        requestDerive()
    }

    private suspend fun derive(updated: State): State.() -> State {
        if (!updated.hasLoaded) return { this }
        val started = System.nanoTime()
        memos.begin()
        val patch = deriveNow(updated)
        val total = (System.nanoTime() - started) / 1_000_000
        com.zyke.message.data.diag.DevLog.d("home") { "state rebuilt in $total ms (${updated.messages.size} messages, ${memos.recomputed} of ${memos.size} parts recomputed)" }
        if (total > SLOW_REBUILD_MS) {
            // Names and times only, never any message text.
            com.zyke.message.data.diag.DevLog.d("home") {
                "slowest parts: " + memos.slowest(SLOWEST_PARTS_LOGGED)
            }
        }
        return patch
    }

    private suspend fun deriveNow(updated: State): State.() -> State {
        run {
            val zone = clock.zone
            val today = LocalDate.ofInstant(updated.now, zone)
            val now = updated.now
            val msgs = updated.messages
            val us = updated.userState
            // Nothing archived (the usual case): the very same list, so everything keyed on it and every cache of it is shared.
            val live = memo("live", msgs, us.archivedIds) { if (us.archivedIds.isEmpty()) msgs else msgs.filter { it.id !in us.archivedIds } }
            val cycle = memo("cycle", updated.plan, msgs, now) { CycleRule.resolve(updated.plan, msgs, now, zone) }
            // The parts that read only the messages and the person's settings are independent pure functions of them:
            // worked out side by side, one task each (the default dispatcher keeps that to the number of cores).
            val parts = kotlinx.coroutines.coroutineScope {
                fun <T> part(work: () -> T) = async(deriveContext) { com.zyke.message.data.perf.LiteRuntime.bounded(work) }
                val dashboard = part {
                    memo("dashboard", msgs, updated.budgets, cycle, now) {
                        DashboardBuilder.build(msgs, DashboardQuery(), now, zone, updated.budgets, cycle)
                    }
                }
                val limits = part {
                    memo("limits", live, updated.budgets, updated.dailyBudgets, updated.budgetAdjustments, now, cycle) {
                        SpendingLimits.build(live, updated.budgets, updated.dailyBudgets, now, zone, cycle, updated.budgetAdjustments)
                    }
                }
                val balances = part { memo("balances", live) { BalanceReader.latest(live, zone) } }
                val insights = part {
                    memo("insights", updated.plan, live, now, cycle) { PlanInsightsBuilder.build(updated.plan, live, now, zone, cycle) }
                }
                val accounts = part {
                    memo("accounts", live, updated.cards, now, cycle, updated.hiddenAccounts, updated.pfAccountKeys, updated.accountOverrides) {
                        Accounts.build(live, updated.cards, now, zone, cycle, updated.hiddenAccounts, updated.pfAccountKeys, updated.accountOverrides)
                    }
                }
                val quickAdds = part {
                    memo("quickAdds", live, updated.plan, updated.cards, us.dismissedAttention, now, updated.budgets) {
                        QuickAdds.find(live, updated.plan, updated.cards, us.dismissedAttention, now, zone, updated.budgets)
                    }
                }
                val attention = part {
                    memo("attention", live, us.dismissedAttention, now, us.dismissedAt) {
                        AttentionBuilder.build(AttentionBuilder.eligible(live), us.dismissedAttention, now, zone, us.dismissedAt)
                    }
                }
                val upcoming = part {
                    memo("upcoming", msgs, now, us.dismissedAttention) {
                        UpcomingBuilder.build(msgs, today, includePaidToday = true, zone = zone, markedPaid = us.dismissedAttention)
                    }
                }
                val review = part {
                    memo("review", live, updated.asides, now, us.dismissedAttention, updated.cancelledSubs) {
                        com.zyke.message.domain.ledger.ReviewQueues.build(live, updated.asides, zone, now, us.dismissedAttention, updated.cancelledSubs)
                    }
                }
                val providentFund = part { memo("pf", live) { com.zyke.message.domain.pf.ProvidentFunds.find(live, zone) } }
                val refunds = part { memo("refunds", live, now) { RefundTracker.build(live, now, zone) } }
                val owed = part { memo("owed", updated.splits, msgs) { SplitInsights.build(updated.splits, msgs, zone).filter { !it.settled } } }
                val subscriptions = part { memo("subs", live, now) { Subscriptions.find(live, now, zone) } }
                val recent = part { memo("recent", live) { live.filter { it.content is MessageContent.Transaction }.take(RECENT_COUNT) } }
                val alerts = part { memo("alerts", live, now) { SpendAlerts.find(live, now.toEpochMilli()) } }
                val agenda = part { memo("agenda", live, now) { LifeAgenda.build(live.filter { !it.isPromotional }, today, zone) } }
                val unread = part { memo("unread", live, us.readIds) { live.count { !it.message.isRead && it.id !in us.readIds } } }
                Parts(
                    dashboard.await(), limits.await(), balances.await(), insights.await(), accounts.await(), quickAdds.await(),
                    attention.await(), upcoming.await(), review.await(), providentFund.await(), refunds.await(), owed.await(),
                    subscriptions.await(), recent.await(), alerts.await(), agenda.await(), unread.await()
                )
            }
            val dashboard = parts.dashboard
            val limits = parts.limits
            val balances = parts.balances
            val insights = parts.insights
            val accounts = parts.accounts
            val quickAdds = parts.quickAdds
            val attention = parts.attention
            val upcoming = parts.upcoming
            val review = parts.review
            val providentFund = parts.providentFund
            val refunds = parts.refunds
            val owed = parts.owed
            val subscriptions = parts.subscriptions
            val recent = parts.recent
            val alerts = parts.alerts
            val agenda = parts.agenda
            val unread = parts.unread
            // What the plan or past spending suggests; the person's own figure, when set, wins over it.
            val dailySuggestion = memo("dailySuggestion", insights, cycle, live, now, updated.plan.items) {
                DailyBudgets.resolve(null, insights, live, today, zone, cycle, updated.plan.items)
            }
            val dailyCustom = updated.dailyOverride
            val daily = memo("daily", dailySuggestion, dailyCustom, cycle, live, now, updated.plan.items) {
                val chosen = if (dailyCustom != null && dailyCustom.signum() > 0) {
                    com.zyke.message.domain.budget.DailyAllowance(dailyCustom, com.zyke.message.domain.budget.DailyBasis.CUSTOM)
                } else dailySuggestion
                chosen?.let {
                    DailyBudgets.status(live, it.amount, now, zone, updated.plan.items).copy(basis = it.basis, cyclesUsed = it.cyclesUsed)
                }
            }
            if (daily == null) {
                com.zyke.message.data.diag.DevLog.d("home") {
                    "no daily budget: custom=${dailyCustom != null}, plan insights=${if (insights == null) "none" else "spendable " + insights.spendable.signum()}, " +
                        "trend=none (too little finished spending), income source=${insights?.incomeSource}, salary entered=${updated.plan.salary != null}, outflows=${updated.plan.outflows.size}"
                }
            } else {
                com.zyke.message.data.diag.DevLog.d("home") { "daily budget basis=${daily.basis} cycles=${daily.cyclesUsed}" }
            }
            val nudges = memo("nudges", limits, now, cycle, live, balances) {
                (PaceNudges.build(limits, emptyList(), now, zone, cycle) + LowBalance.warnings(live, balances, today)).take(MAX_NUDGES)
            }
            val goals = Goals.all(updated.goalList, today)
            val reminders = Reminders.upcoming(updated.reminderList, today)
            // What the person put out of sight: keyed on the day too, since a snooze runs out by itself.
            val hides = updated.planActions
            val planAgenda = memo("planAgenda", agenda, hides, today) {
                PlanActions.visible(agenda, hides, today) { PlanKeys.agenda(it) }
            }
            val planUpcoming = memo("planUpcoming", upcoming, hides, today) {
                PlanActions.visible(upcoming, hides, today) { PlanKeys.bill(it) }
            }
            val planReminders = memo("planReminders", reminders, hides, today) {
                PlanActions.visible(reminders, hides, today) { PlanKeys.reminder(it.reminder) }
            }
            val outflow = memo("outflow", upcoming, updated.loanList, updated.reminderList, updated.plan, live, updated.recurringStates, updated.cancelledSubs, today) {
                com.zyke.message.domain.forecast.UpcomingOutflows.build(
                    from = today, upcoming = upcoming, loans = updated.loanList, reminders = updated.reminderList, plan = updated.plan,
                    // The same subscriptions the part above found for these messages and this moment.
                    recurring = com.zyke.message.domain.insights.RecurringPayments.arrange(subscriptions, updated.recurringStates, updated.cancelledSubs.keys)
                )
            }
            // The subscriptions page: every recurring charge with its yearly cost, state, hints and how to stop it.
            val subscriptionOverview = memo("subscriptionOverview", subscriptions, updated.recurringStates, updated.cancelledSubs, live, today) {
                com.zyke.message.domain.insights.SubscriptionOverview.build(subscriptions, updated.recurringStates, updated.cancelledSubs, today, live, zone)
            }
            val budgetStatus = memo("budgetStatus", dashboard) {
                dashboard.categories.filter { it.budget != null }.sortedByDescending { it.usedFraction ?: 0.0 }
            }
            return {
                copy(
                    cycle = cycle, daily = daily, dailySuggestion = dailySuggestion, goals = goals, reminders = reminders, providentFund = providentFund,
                    accounts = accounts, quickAdds = quickAdds, nudges = nudges, refunds = refunds, owed = owed,
                    subscriptions = subscriptions, subscriptionOverview = subscriptionOverview, attention = attention, review = review, thisMonth = dashboard,
                    upcoming = upcoming, outflow = outflow, budgetStatus = budgetStatus, recent = recent, alerts = alerts,
                    agenda = agenda, unreadCount = unread,
                    planAgenda = planAgenda, planUpcoming = planUpcoming, planReminders = planReminders
                )
            }
        }
    }

    /** What the parts worked out side by side came to. */
    private class Parts(
        val dashboard: com.zyke.message.domain.dashboard.Dashboard,
        val limits: List<com.zyke.message.domain.dashboard.CategoryLimits>,
        val balances: List<com.zyke.message.domain.insights.AccountBalance>,
        val insights: com.zyke.message.domain.plan.PlanInsights?,
        val accounts: com.zyke.message.domain.accounts.AccountsOverview,
        val quickAdds: List<QuickAdd>,
        val attention: List<com.zyke.message.domain.feed.AttentionItem>,
        val upcoming: List<com.zyke.message.domain.home.UpcomingItem>,
        val review: com.zyke.message.domain.ledger.ReviewQueue,
        val providentFund: com.zyke.message.domain.pf.ProvidentFund?,
        val refunds: List<com.zyke.message.domain.insights.RefundItem>,
        val owed: List<com.zyke.message.domain.split.SplitShare>,
        val subscriptions: List<com.zyke.message.domain.insights.Subscription>,
        val recent: List<com.zyke.message.domain.model.ClassifiedMessage>,
        val alerts: List<com.zyke.message.domain.insights.SpendAlert>,
        val agenda: List<com.zyke.message.domain.life.AgendaItem>,
        val unread: Int
    )

    private fun <V> memo(name: String, vararg inputs: Any?, compute: () -> V): V = memos.of(name, *inputs, compute = compute)

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
        private val calendarEvents: com.zyke.message.domain.repository.CalendarEventRepository = com.zyke.message.domain.repository.NoCalendarEvents,
        private val setAsideRepository: com.zyke.message.domain.ledger.SetAsideRepository = com.zyke.message.domain.ledger.NoSetAsides,
        private val manualEntryRepository: com.zyke.message.domain.ledger.ManualEntryRepository = com.zyke.message.domain.ledger.NoManualEntries,
        private val merges: com.zyke.message.domain.repository.MergeRepository = com.zyke.message.domain.repository.NoMerges,
        private val recurring: com.zyke.message.domain.repository.RecurringConfirmationRepository = com.zyke.message.domain.repository.NoRecurringConfirmations,
        private val loanRepository: com.zyke.message.domain.repository.LoanRepository = com.zyke.message.domain.repository.NoLoans,
        private val planActionRepository: com.zyke.message.domain.repository.PlanActionRepository = com.zyke.message.domain.repository.NoPlanActions,
        private val dailyOverride: com.zyke.message.domain.budget.DailyBudgetOverrideRepository = com.zyke.message.domain.budget.NoDailyBudgetOverride,
        private val cancelledSubscriptions: com.zyke.message.domain.repository.CancelledSubscriptionRepository = com.zyke.message.domain.repository.NoCancelledSubscriptions,
        private val accountOverrides: com.zyke.message.domain.accounts.AccountOverrideRepository = com.zyke.message.domain.accounts.NoAccountOverrides
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            HomeViewModel(
                observeMessages, userStateRepository, budgetRepository,
                categoryOverrides = categoryOverrides, mutedSenders = mutedSenders, wishlist = wishlist, splitRepository = splits, nicknames = nicknames,
                planRepository = planRepository, cardRepository = cardRepository,
                goalRepository = goalRepository, reminderRepository = reminderRepository, hiddenAccounts = hiddenAccounts, pfAccounts = pfAccounts, calendarEvents = calendarEvents,
                setAsideRepository = setAsideRepository, manualEntryRepository = manualEntryRepository, merges = merges,
                recurring = recurring, loanRepository = loanRepository, planActionRepository = planActionRepository, dailyOverride = dailyOverride,
                cancelledSubscriptions = cancelledSubscriptions, accountOverrides = accountOverrides
            ) as T
    }

    private companion object {
        const val RECENT_COUNT = 5
        const val MAX_NUDGES = 4
        const val SLOW_REBUILD_MS = 150L
        const val SLOWEST_PARTS_LOGGED = 5
    }
}
