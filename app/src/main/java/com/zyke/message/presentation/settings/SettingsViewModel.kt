package com.zyke.message.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.diagnostics.DiagnosticsBuilder
import com.zyke.message.domain.model.ClassifiedMessage
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
    private val planRepository: MoneyPlanRepository = NoMoneyPlan
) : MviViewModel<State, Intent, Effect>(
    State(settings = settingsRepository.settings.value, cards = cardRepository.cards.value, plan = planRepository.plan.value)
) {

    private var observeJob: Job? = null
    private var inbox: List<ClassifiedMessage> = emptyList()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> reduce { copy(settings = settings) } }
        }
        viewModelScope.launch {
            planRepository.plan.collect { plan -> reduce { copy(plan = plan) } }
        }
        viewModelScope.launch {
            budgetRepository.budgets.collect { monthly -> reduce { copy(monthlyLimits = monthly) }; recomputeLimits() }
        }
        viewModelScope.launch {
            budgetRepository.dailyBudgets.collect { daily -> reduce { copy(dailyLimits = daily) }; recomputeLimits() }
        }
        viewModelScope.launch {
            cardRepository.cards.collect { cards -> reduce { copy(cards = cards) } }
        }
    }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted -> if (intent.permissionGranted) startObserving()
            is Intent.SetScreenProtection -> settingsRepository.setScreenProtection(intent.enabled)
            is Intent.SetThemeMode -> settingsRepository.setThemeMode(intent.mode)
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
                emitEffect(Effect.ShowMessage("Limits saved for ${intent.category.displayString}"))
            }
            is Intent.SetSalary -> planRepository.save(
                planRepository.plan.value.copy(
                    salary = amountOf(intent.amount),
                    salaryDay = intent.day.trim().toIntOrNull()?.takeIf { it in 1..MAX_DAY }
                )
            )
            is Intent.SetSavingsGoal -> planRepository.save(planRepository.plan.value.copy(savingsGoal = amountOf(intent.amount)))
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
            is Intent.RemoveLimits -> {
                budgetRepository.setDailyBudget(intent.category, null)
                budgetRepository.setBudget(intent.category, null)
                emitEffect(Effect.ShowMessage("Limits removed"))
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
        }
    }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = viewModelScope.launch {
            observeMessages()
                .catch { reduce { copy(diagnostics = null) } }
                .collect { messages ->
                    inbox = messages
                    reduce { copy(diagnostics = DiagnosticsBuilder.build(messages)) }
                    recomputeLimits()
                }
        }
    }

    /** Where each limit stands, from the latest inbox and the limits as they are now. */
    private fun recomputeLimits() {
        val state = currentState()
        val limits = SpendingLimits.build(inbox, state.monthlyLimits, state.dailyLimits, clock.instant(), clock.zone)
        reduce { copy(limits = limits) }
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
        private val planRepository: MoneyPlanRepository = NoMoneyPlan
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            SettingsViewModel(
                observeMessages, settingsRepository, userStateRepository, budgetRepository,
                cardRepository = cardRepository, onLogosTurnedOff = onLogosTurnedOff, onDigestChanged = onDigestChanged,
                onLiveAlertsChanged = onLiveAlertsChanged, planRepository = planRepository
            ) as T
    }
}
