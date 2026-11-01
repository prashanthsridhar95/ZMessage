package com.zyke.message.presentation.settings

import com.zyke.message.domain.cycle.CycleRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.dashboard.Budgets
import com.zyke.message.domain.dashboard.CarryOver
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.diagnostics.DiagnosticsBuilder
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.plan.SalaryDetector
import java.math.BigDecimal
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.CardRepository
import com.zyke.message.domain.repository.MoneyPlanRepository
import com.zyke.message.domain.repository.NoMoneyPlan
import com.zyke.message.domain.repository.NoCards
import com.zyke.message.domain.repository.SettingsRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.mvi.MviViewModel
import com.zyke.message.presentation.settings.SettingsContract.Effect
import com.zyke.message.presentation.settings.SettingsContract.Intent
import com.zyke.message.presentation.settings.SettingsContract.State
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val observeMessages: ObserveMessagesUseCase,
    private val settingsRepository: SettingsRepository,
    private val userStateRepository: UserMessageStateRepository,
    private val budgetRepository: BudgetRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val cardRepository: CardRepository = NoCards,
    /** Called when downloaded logos should be forgotten (the person turned the feature off). */
    private val onLogosTurnedOff: () -> Unit = {},
    /** Called when the morning digest is switched on or off, so its alarm can be set or cancelled. */
    private val onDigestChanged: (Boolean) -> Unit = {},
    /** Called when live alerts are switched on or off, so the SMS receiver can be enabled or disabled. */
    private val onLiveAlertsChanged: (Boolean) -> Unit = {},
    /** Called when the weekly summary is switched on or off, so its alarm can be set or cancelled. */
    private val onWeeklySummaryChanged: (Boolean) -> Unit = {},
    private val planRepository: MoneyPlanRepository = NoMoneyPlan,
    private val hiddenAccounts: com.zyke.message.domain.repository.HiddenAccountRepository = com.zyke.message.domain.repository.NoHiddenAccounts,
    private val accountOverrides: com.zyke.message.domain.accounts.AccountOverrideRepository = com.zyke.message.domain.accounts.NoAccountOverrides,
    /** The person's own setup beyond what this screen holds (goals, reminders, events, entries), for the export. */
    private val setupExtras: () -> org.json.JSONObject = { org.json.JSONObject() },
    /** Where limits and diagnostics are worked out; never the main thread outside tests. */
    private val deriveContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default
) : MviViewModel<State, Intent, Effect>(
    State(settings = settingsRepository.settings.value, cards = cardRepository.cards.value, plan = planRepository.plan.value)
) {

    private var observeJob: Job? = null
    @Volatile private var inbox: List<ClassifiedMessage> = emptyList()

    init {
        startDeriving("settings", deriveContext) { snapshot -> deriveLimits(snapshot) }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> reduce { copy(settings = settings) } }
        }
        viewModelScope.launch {
            hiddenAccounts.hidden.collect { recomputeLimits() }
        }
        viewModelScope.launch {
            accountOverrides.overrides.collect { recomputeLimits() }
        }
        viewModelScope.launch {
            planRepository.plan.collect { plan -> reduce { copy(plan = plan) }; recomputeLimits() }
        }
        viewModelScope.launch {
            budgetRepository.budgets.collect { monthly -> reduce { copy(monthlyLimits = monthly) }; recomputeLimits() }
        }
        viewModelScope.launch {
            budgetRepository.dailyBudgets.collect { daily -> reduce { copy(dailyLimits = daily) }; recomputeLimits() }
        }
        viewModelScope.launch {
            budgetRepository.adjustments.collect { adjustments -> reduce { copy(budgetAdjustments = adjustments) }; recomputeLimits() }
        }
        viewModelScope.launch {
            cardRepository.cards.collect { cards -> reduce { copy(cards = cards) }; recomputeLimits() }
        }
    }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted -> if (intent.permissionGranted) startObserving()
            is Intent.SetScreenProtection -> settingsRepository.setScreenProtection(intent.enabled)
            is Intent.SetThemeMode -> settingsRepository.setThemeMode(intent.mode)
            is Intent.SetCustomAccent -> settingsRepository.setCustomAccent(intent.argb)
            is Intent.SetAppIcon -> settingsRepository.setAppIcon(intent.icon)
            is Intent.SetAccent -> settingsRepository.setAccent(intent.accent)
            is Intent.SetHideAmounts -> settingsRepository.setHideAmounts(intent.enabled)
            is Intent.SetRevealDuration -> settingsRepository.setRevealDuration(intent.duration)
            is Intent.SetAuthForAmounts -> settingsRepository.setAuthForAmounts(intent.enabled)
            is Intent.SetFetchLogos -> {
                settingsRepository.setFetchLogos(intent.enabled)
                if (!intent.enabled) onLogosTurnedOff()
            }
            is Intent.SetAppLock -> settingsRepository.setAppLock(intent.enabled)
            is Intent.SetLiveAlerts -> {
                settingsRepository.setLiveAlerts(intent.enabled)
                onLiveAlertsChanged(intent.enabled)
            }
            is Intent.SetMorningDigest -> {
                settingsRepository.setMorningDigest(intent.enabled)
                onDigestChanged(intent.enabled)
            }
            is Intent.SetWeeklySummary -> {
                settingsRepository.setWeeklySummary(intent.enabled)
                onWeeklySummaryChanged(intent.enabled)
            }
            is Intent.SetHeadsUp -> settingsRepository.setHeadsUp(intent.enabled)
            is Intent.SetLowBalanceFloor -> settingsRepository.setLowBalanceFloor(amountOf(intent.text))
            is Intent.SaveCard -> {
                val card = intent.card
                if (card.last4.length == LAST_DIGITS && card.last4.all { it.isDigit() }) {
                    cardRepository.save(card)
                    emitEffect(Effect.ShowMessage("${card.label} saved"))
                }
            }
            is Intent.SetLimits -> {
                budgetRepository.setDailyBudget(intent.category, amountOf(intent.daily))
                budgetRepository.setBudget(intent.category, amountOf(intent.monthly))
                val start = currentState().cycleRange?.start
                if (intent.carry != null && start != null) {
                    budgetRepository.saveAdjustments(Budgets.setCarry(budgetRepository.adjustments.value, intent.category, intent.carry, start))
                }
                emitEffect(Effect.ShowMessage("Budget saved for ${intent.category.displayString}"))
            }
            is Intent.MoveBudget -> {
                val status = currentState().budgetStatus
                val amount = amountOf(intent.amount)
                val before = budgetRepository.adjustments.value
                val after = if (status != null && amount != null) Budgets.move(before, status, intent.from, intent.to, amount) else before
                if (after != before) {
                    budgetRepository.saveAdjustments(after)
                    emitEffect(Effect.ShowMessage("Moved ${com.zyke.message.presentation.format.DisplayFormatters.rupees(amount!!)} to ${intent.to.displayString}"))
                } else {
                    emitEffect(Effect.ShowMessage("That is more than ${intent.from.displayString} has left"))
                }
            }
            Intent.UndoBudgetMove -> currentState().cycleRange?.start?.let {
                budgetRepository.saveAdjustments(Budgets.undoMove(budgetRepository.adjustments.value, it))
            }
            is Intent.SetSalary -> {
                // "last" stands for the last working day of the month; anything else is a day number or blank.
                val last = intent.day.trim() == "last"
                planRepository.save(
                    planRepository.plan.value.copy(
                        salary = amountOf(intent.amount),
                        salaryDay = if (last) null else intent.day.trim().toIntOrNull()?.takeIf { it in 1..MAX_DAY },
                        salaryOnLastWorkingDay = last
                    )
                )
            }
            is Intent.SetSavingsGoal -> planRepository.save(planRepository.plan.value.copy(savingsGoal = amountOf(intent.amount)))
            is Intent.SetCycle -> planRepository.save(
                planRepository.plan.value.copy(
                    cycleMode = intent.mode,
                    cycleDay = intent.day.trim().toIntOrNull()?.takeIf { it in 1..MAX_DAY }
                )
            )
            is Intent.SavePlanItem -> {
                val plan = planRepository.plan.value
                val exists = plan.items.any { it.id == intent.item.id }
                planRepository.save(
                    plan.copy(items = if (exists) plan.items.map { if (it.id == intent.item.id) intent.item else it } else plan.items + intent.item)
                )
            }
            is Intent.DeletePlanItem -> {
                val plan = planRepository.plan.value
                planRepository.save(plan.copy(items = plan.items.filter { it.id != intent.id }))
            }
            is Intent.SetCardLimit -> {
                planRepository.save(planRepository.plan.value.copy(cardSpendLimit = amountOf(intent.amount)))
                emitEffect(Effect.ShowMessage(if (amountOf(intent.amount) == null) "Card limit removed" else "Card limit saved"))
            }
            is Intent.RemoveLimits -> {
                budgetRepository.setDailyBudget(intent.category, null)
                budgetRepository.setBudget(intent.category, null)
                currentState().cycleRange?.start?.let {
                    budgetRepository.saveAdjustments(Budgets.setCarry(budgetRepository.adjustments.value, intent.category, CarryOver.RESET, it))
                }
                emitEffect(Effect.ShowMessage("Budget removed"))
            }
            is Intent.DeleteCard -> {
                cardRepository.remove(intent.id)
                emitEffect(Effect.ShowMessage("Removed"))
            }
            Intent.AskReset -> reduce { copy(confirmingReset = true) }
            Intent.CancelReset -> reduce { copy(confirmingReset = false) }
            Intent.ConfirmReset -> {
                userStateRepository.clearAll()
                budgetRepository.clearAll()
                reduce { copy(confirmingReset = false) }
                emitEffect(Effect.ShowMessage("Marks and budgets cleared"))
            }
            Intent.ShareDiagnostics -> shareDiagnostics()
            is Intent.SetReadingPaused -> settingsRepository.setReadingPaused(intent.paused)
            Intent.ExportTransactions -> {
                val csv = com.zyke.message.domain.export.Exports.transactionsCsv(inbox, clock.zone)
                val stamp = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT))
                emitEffect(Effect.ShareData("zyke-transactions-$stamp.csv", csv, "text/csv", "Zyke Message transactions"))
            }
            Intent.ExportSetup -> {
                val state = currentState()
                val json = setupExtras().apply {
                    put("plan", org.json.JSONObject().apply {
                        put("salary", state.plan.salary?.toPlainString())
                        put("salaryDay", state.plan.salaryDay)
                        put("salaryOnLastWorkingDay", state.plan.salaryOnLastWorkingDay)
                        put("savingsGoal", state.plan.savingsGoal?.toPlainString())
                        put("cardSpendLimit", state.plan.cardSpendLimit?.toPlainString())
                        put("cycle", state.plan.cycleMode.name)
                        put("cycleDay", state.plan.cycleDay)
                        put("items", org.json.JSONArray().apply {
                            state.plan.items.forEach { put(org.json.JSONObject().put("kind", it.kind.name).put("name", it.name).put("amount", it.amount.toPlainString()).put("day", it.dayOfMonth)) }
                        })
                    })
                    put("monthlyLimits", org.json.JSONObject().apply { state.monthlyLimits.forEach { (c, v) -> put(c.displayString, v.toPlainString()) } })
                    put("dailyLimits", org.json.JSONObject().apply { state.dailyLimits.forEach { (c, v) -> put(c.displayString, v.toPlainString()) } })
                    put("budgetCarryOver", org.json.JSONObject().apply { state.budgetAdjustments.carry.forEach { (c, v) -> put(c.displayString, v.name) } })
                    put("cards", org.json.JSONArray().apply {
                        state.cards.forEach { put(org.json.JSONObject().put("type", it.type.name).put("last4", it.last4).put("name", it.name).put("bank", it.bankName).put("statementDay", it.statementDay)) }
                    })
                }
                val stamp = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT))
                emitEffect(Effect.ShareData("zyke-setup-$stamp.json", json.toString(2), "application/json", "Zyke Message setup"))
            }
        }
    }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = viewModelScope.launch {
            observeMessages.calm()
                .catch { reduce { copy(diagnostics = null) } }
                .collect { messages ->
                    inbox = messages
                    val diagnostics = kotlinx.coroutines.withContext(deriveContext) { DiagnosticsBuilder.build(messages) }
                    reduce { copy(diagnostics = diagnostics) }
                    recomputeLimits()
                }
        }
    }

    /** Where each limit stands, from the latest inbox and the limits as they are now. */
    private fun recomputeLimits() = requestDerive()

    private fun deriveLimits(state: State): State.() -> State {
        val inbox = inbox
        val cycle = CycleRule.resolve(state.plan, inbox, clock.instant(), clock.zone)
        val limits = SpendingLimits.build(inbox, state.monthlyLimits, state.dailyLimits, clock.instant(), clock.zone, cycle, state.budgetAdjustments)
        val spotted = SalaryDetector.detect(inbox, clock.instant(), clock.zone, state.plan)
        val range = cycle.containing(java.time.LocalDate.ofInstant(clock.instant(), clock.zone))
        val accounts = com.zyke.message.domain.accounts.Accounts.build(
            inbox, state.cards, clock.instant(), clock.zone, cycle, hiddenAccounts.hidden.value,
            overrides = accountOverrides.overrides.value
        ).accounts
        val spendable = com.zyke.message.domain.plan.PlanInsightsBuilder.build(state.plan, inbox, clock.instant(), clock.zone, cycle)?.spendable
        val suggestions = com.zyke.message.domain.forecast.LimitSuggestions.build(inbox, state.monthlyLimits, spendable, clock.instant(), clock.zone, cycle)
        val cardSpend = com.zyke.message.domain.accounts.CardSpend.status(accounts, state.plan.cardSpendLimit)
        val budgetStatus = Budgets.status(
            state.monthlyLimits, state.budgetAdjustments, inbox, cycle, java.time.LocalDate.ofInstant(clock.instant(), clock.zone), clock.zone, spendable
        )
        return {
            copy(
                limits = limits, spottedSalary = spotted, cycleRange = range, accounts = accounts,
                spendable = spendable, limitSuggestions = suggestions, cardSpend = cardSpend, budgetStatus = budgetStatus
            )
        }
    }

    /** A blank or unusable entry clears the limit rather than keeping a stale one. */
    private fun amountOf(text: String): BigDecimal? =
        text.trim().replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    private fun shareDiagnostics() {
        val diagnostics = currentState().diagnostics ?: return
        if (diagnostics.samples.isEmpty()) {
            emitEffect(Effect.ShowMessage("Nothing to report: every money message was understood"))
            return
        }
        val report = buildString {
            appendLine("Zyke Message parsing report")
            appendLine("Money messages: ${diagnostics.moneyMessages}, not understood: ${diagnostics.unrecognised}")
            appendLine("(numbers, names, links and handles are masked)")
            diagnostics.samples.forEachIndexed { index, sample ->
                appendLine()
                appendLine("--- ${index + 1} ---")
                appendLine(sample)
            }
        }
        val stamp = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT))
        emitEffect(Effect.ShareReport("zyke-parsing-report-$stamp.txt", report))
    }

    private companion object {
        const val LAST_DIGITS = 4
        const val MAX_DAY = 31
    }

    class Factory(
        private val observeMessages: ObserveMessagesUseCase,
        private val settingsRepository: SettingsRepository,
        private val userStateRepository: UserMessageStateRepository,
        private val budgetRepository: BudgetRepository,
        private val cardRepository: CardRepository = NoCards,
        private val onLogosTurnedOff: () -> Unit = {},
        private val onDigestChanged: (Boolean) -> Unit = {},
        private val onLiveAlertsChanged: (Boolean) -> Unit = {},
        private val onWeeklySummaryChanged: (Boolean) -> Unit = {},
        private val planRepository: MoneyPlanRepository = NoMoneyPlan,
        private val hiddenAccounts: com.zyke.message.domain.repository.HiddenAccountRepository = com.zyke.message.domain.repository.NoHiddenAccounts,
        private val accountOverrides: com.zyke.message.domain.accounts.AccountOverrideRepository = com.zyke.message.domain.accounts.NoAccountOverrides,
        private val setupExtras: () -> org.json.JSONObject = { org.json.JSONObject() }
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            SettingsViewModel(
                observeMessages, settingsRepository, userStateRepository, budgetRepository,
                cardRepository = cardRepository, onLogosTurnedOff = onLogosTurnedOff, onDigestChanged = onDigestChanged,
                onLiveAlertsChanged = onLiveAlertsChanged, onWeeklySummaryChanged = onWeeklySummaryChanged, planRepository = planRepository, hiddenAccounts = hiddenAccounts, accountOverrides = accountOverrides, setupExtras = setupExtras
            ) as T
    }
}
