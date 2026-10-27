package com.zyke.message.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.diagnostics.DiagnosticsBuilder
import com.zyke.message.domain.repository.BudgetRepository
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
    private val clock: Clock = Clock.systemDefaultZone()
) : MviViewModel<State, Intent, Effect>(State(settings = settingsRepository.settings.value)) {

    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> reduce { copy(settings = settings) } }
        }
    }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted -> if (intent.permissionGranted) startObserving()
            is Intent.SetScreenProtection -> settingsRepository.setScreenProtection(intent.enabled)
            is Intent.SetThemeMode -> settingsRepository.setThemeMode(intent.mode)
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
                .collect { inbox -> reduce { copy(diagnostics = DiagnosticsBuilder.build(inbox)) } }
        }
    }

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

    class Factory(
        private val observeMessages: ObserveMessagesUseCase,
        private val settingsRepository: SettingsRepository,
        private val userStateRepository: UserMessageStateRepository,
        private val budgetRepository: BudgetRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            SettingsViewModel(observeMessages, settingsRepository, userStateRepository, budgetRepository) as T
    }
}
