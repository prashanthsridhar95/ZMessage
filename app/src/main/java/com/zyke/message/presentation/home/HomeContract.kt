package com.zyke.message.presentation.home

import com.zyke.message.domain.dashboard.CategoryTotal
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.domain.home.UpcomingItem
import com.zyke.message.domain.accounts.AccountsOverview
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.goals.GoalProgress
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.reminders.ReminderDue
import com.zyke.message.domain.budget.DailyBudgetStatus
import com.zyke.message.domain.insights.Nudge
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.QuickAdd
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.insights.RefundItem
import com.zyke.message.domain.insights.SpendAlert
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.life.AgendaItem
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.split.Split
import com.zyke.message.domain.split.SplitShare
import com.zyke.message.domain.wishlist.WishItem
import java.math.BigDecimal
import java.time.Instant

object HomeContract {

    data class State(
        val isLoading: Boolean = true,
        val permissionMissing: Boolean = false,
        val hasError: Boolean = false,
        val hasLoaded: Boolean = false,

        // Inputs
        val messages: List<ClassifiedMessage> = emptyList(),
        val userState: UserMessageState = UserMessageState(),
        val budgets: Map<MessageCategory, BigDecimal> = emptyMap(),
        val dailyBudgets: Map<MessageCategory, BigDecimal> = emptyMap(),
        val plan: MoneyPlan = MoneyPlan(),
        val goalList: List<Goal> = emptyList(),
        val reminderList: List<Reminder> = emptyList(),
        val cards: List<UserCard> = emptyList(),
        val wishlist: List<WishItem> = emptyList(),
        val splits: List<Split> = emptyList(),
        val now: Instant = Instant.EPOCH,

        // Derived
        val attention: List<AttentionItem> = emptyList(),
        /** This month's spending, against last month. */
        val thisMonth: Dashboard? = null,
        val upcoming: List<UpcomingItem> = emptyList(),
        /** Budgeted categories, the most used first. */
        val budgetStatus: List<CategoryTotal> = emptyList(),
        val recent: List<ClassifiedMessage> = emptyList(),
        val unreadCount: Int = 0,
        /** Odd-looking payments from the last two weeks. */
        val alerts: List<SpendAlert> = emptyList(),
        /** Short, numeric heads-ups: pacing against limits, and an account too low for a coming debit. */
        val nudges: List<Nudge> = emptyList(),
        /** Refunds announced by messages, waiting or recently arrived. */
        val refunds: List<RefundItem> = emptyList(),
        /** Shares of shared payments that have not come back yet. */
        val owed: List<SplitShare> = emptyList(),
        /** Payments that repeat monthly, soonest due first. */
        val subscriptions: List<Subscription> = emptyList(),
        /** Today's share of what is spendable, how much of it is left, and the run of days kept within it; null with no plan. */
        val daily: DailyBudgetStatus? = null,
        /** Every bank account and card the messages mention, and what the accounts hold in all. */
        /** Goals with how far along each is, and reminders with the day each next falls on. */
        val goals: List<GoalProgress> = emptyList(),
        val reminders: List<ReminderDue> = emptyList(),
        val accounts: AccountsOverview = AccountsOverview(emptyList(), null, 0),
        /** Things the messages show that the person has not entered yet. */
        val quickAdds: List<QuickAdd> = emptyList(),
        /** Appointments, trips and renewals in the next two weeks, and orders on their way. */
        val agenda: List<AgendaItem> = emptyList(),

        /** The message whose sheet is open, and the budgeted category whose sheet is open. */
        val detailMessageId: Long? = null,
        val budgetDetail: MessageCategory? = null,
        /** The pay cycle shown in the budget calendar while it is open (0 is this one, -1 the one before), and whether the quick-add sheet is open. */
        val calendarOffset: Int? = null,
        /** Where the person's month starts, from their settings. */
        val cycle: com.zyke.message.domain.cycle.CycleRule = com.zyke.message.domain.cycle.CycleRule.CALENDAR,
        val showQuickAdds: Boolean = false,
        /** The account whose sheet is open, and whether the list of codes is. */
        val openAccountKey: String? = null,
        val showCodes: Boolean = false,
        val showGoals: Boolean = false,
        val showReminders: Boolean = false,
        /** The trip whose scene is open. */
        val journeyMessageId: Long? = null,
        /** The payment being split with friends, while its dialog is open. */
        val splittingMessageId: Long? = null
    ) {
        val splitting: ClassifiedMessage? get() = splittingMessageId?.let { id -> messages.firstOrNull { it.id == id } }

        val detail: ClassifiedMessage? get() = detailMessageId?.let { id -> messages.firstOrNull { it.id == id } }

        /** Nothing money-related has been seen yet. */
        val isBlank: Boolean
            get() = hasLoaded && attention.isEmpty() && upcoming.isEmpty() && recent.isEmpty() && thisMonth?.isEmpty != false
    }

    sealed interface Intent {
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class CopyOtp(val messageId: Long, val code: String) : Intent
        data class DismissAttention(val key: String) : Intent
        data class OpenLink(val url: String) : Intent
        data class OpenMessage(val messageId: Long) : Intent
        data object CloseMessage : Intent
        data class OpenBudget(val category: MessageCategory) : Intent
        data object CloseBudget : Intent
        data class TogglePin(val messageId: Long) : Intent
        data class Archive(val messageId: Long) : Intent
        data class Unarchive(val messageId: Long) : Intent
        data class Rename(val messageId: Long, val name: String) : Intent
        data object OpenCalendar : Intent
        data object CloseCalendar : Intent
        data class ShiftCalendar(val months: Int) : Intent
        data class SaveGoal(val goal: Goal) : Intent
        data class RemoveGoal(val id: String) : Intent
        data class AddToGoal(val id: String, val amount: java.math.BigDecimal) : Intent
        data class SaveReminder(val reminder: Reminder) : Intent
        data class RemoveReminder(val id: String) : Intent
        data class OpenAccount(val key: String) : Intent
        data object CloseAccount : Intent
        data class NameAccount(val key: String, val name: String) : Intent
        data class OpenJourney(val messageId: Long) : Intent
        data object CloseJourney : Intent
        data object OpenGoals : Intent
        data object CloseGoals : Intent
        data object OpenReminders : Intent
        data object CloseReminders : Intent
        data object OpenCodes : Intent
        data object CloseCodes : Intent
        data object OpenQuickAdds : Intent
        data object CloseQuickAdds : Intent
        data class AddQuick(val quick: QuickAdd) : Intent
        data class DismissQuick(val key: String) : Intent
        data class StartSplit(val messageId: Long) : Intent
        data object CancelSplit : Intent
        data class SaveSplit(val split: Split) : Intent
        data class SettleShare(val splitId: String, val person: String) : Intent
        data class RemoveSplit(val id: String) : Intent
        data class SetCategory(val messageId: Long, val category: MessageCategory?) : Intent
        data class ToggleMute(val messageId: Long) : Intent
        /** Try again after the inbox could not be read. */
        data object Retry : Intent
    }

    sealed interface Effect {
        data class CopyToClipboard(val label: String, val text: String) : Effect
        data class OpenLink(val url: String) : Effect
    }
}
