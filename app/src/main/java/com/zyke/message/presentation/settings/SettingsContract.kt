package com.zyke.message.presentation.settings

import com.zyke.message.domain.diagnostics.ParsingDiagnostics
import com.zyke.message.domain.dashboard.CategoryLimits
import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.ThemeMode
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.SalaryPattern
import java.math.BigDecimal

object SettingsContract {

    data class State(
        val settings: AppSettings = AppSettings(),
        /** Null until the inbox has been read (or when SMS access is missing). */
        val diagnostics: ParsingDiagnostics? = null,
        val confirmingReset: Boolean = false,
        val cards: List<UserCard> = emptyList(),
        /** Categories with a daily and / or monthly limit, and where they stand today and this month. */
        val limits: List<CategoryLimits> = emptyList(),
        val dailyLimits: Map<MessageCategory, BigDecimal> = emptyMap(),
        val monthlyLimits: Map<MessageCategory, BigDecimal> = emptyMap(),
        /** What the person has told the app about their month. */
        val plan: MoneyPlan = MoneyPlan(),
        /** The pay cycle now in force, from the person's choice. */
        val cycleRange: com.zyke.message.domain.dashboard.DateRange? = null,
        /** Every card and account the app knows: those found in the messages and those the person added. */
        val accounts: List<com.zyke.message.domain.accounts.AccountSummary> = emptyList(),
        /** What the plan leaves to spend in a month, once what is committed is taken out. */
        val spendable: BigDecimal? = null,
        /** Monthly limits worked out from the plan and from what has been spent, to set in one tap. */
        val limitSuggestions: List<com.zyke.message.domain.forecast.LimitSuggestion> = emptyList(),
        /** Credit card spending against the one limit set for all cards. */
        val cardSpend: com.zyke.message.domain.accounts.CardSpendStatus? = null,
        /** The salary the messages show arriving each month, if there is one. It is used ahead of the entered amount. */
        val spottedSalary: SalaryPattern? = null
    )

    sealed interface Intent {
        /** Starts reading the inbox for diagnostics, if SMS access is granted. */
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class SetScreenProtection(val enabled: Boolean) : Intent
        data class SetThemeMode(val mode: ThemeMode) : Intent
        data class SetFetchLogos(val enabled: Boolean) : Intent
        data class SetAppLock(val enabled: Boolean) : Intent
        data class SetMorningDigest(val enabled: Boolean) : Intent
        data class SetLiveAlerts(val enabled: Boolean) : Intent
        data class SetWeeklySummary(val enabled: Boolean) : Intent
        data class SetHeadsUp(val enabled: Boolean) : Intent
        data class SetLowBalanceFloor(val text: String) : Intent
        data class SaveCard(val card: UserCard) : Intent
        data class DeleteCard(val id: String) : Intent
        /** Sets (or, for a blank value, clears) the daily and monthly limit of a category. */
        data class SetLimits(val category: MessageCategory, val daily: String, val monthly: String) : Intent
        data class RemoveLimits(val category: MessageCategory) : Intent
        /** The most to spend on credit cards in a month, all cards together; blank clears it. */
        data class SetCardLimit(val amount: String) : Intent
        /** The usual monthly salary and the day it arrives; a blank amount clears it. */
        data class SetSalary(val amount: String, val day: String) : Intent
        data class SetSavingsGoal(val amount: String) : Intent
        /** Where the month starts; [day] is only used for the day-of-month choice. */
        data class SetCycle(val mode: com.zyke.message.domain.cycle.CycleMode, val day: String) : Intent
        data class SavePlanItem(val item: PlanItem) : Intent
        data class DeletePlanItem(val id: String) : Intent
        data object AskReset : Intent
        data object CancelReset : Intent
        data object ConfirmReset : Intent
        data object ShareDiagnostics : Intent
        /** Stops reading the phone's messages and listening for new ones, or starts again. */
        data class SetReadingPaused(val paused: Boolean) : Intent
        /** The transactions read so far, as a spreadsheet. */
        data object ExportTransactions : Intent
        /** The person's own setup (plan, limits, cards, goals, reminders, events, entries), as a file. */
        data object ExportSetup : Intent
    }

    sealed interface Effect {
        /** Write [text] to a file named [fileName] and offer it to the apps that can receive it. */
        data class ShareReport(val fileName: String, val text: String) : Effect
        data class ShowMessage(val text: String) : Effect
        /** A file made from the person's own data, to offer to whichever app they pick. */
        data class ShareData(val fileName: String, val text: String, val mimeType: String, val subject: String) : Effect
    }
}
