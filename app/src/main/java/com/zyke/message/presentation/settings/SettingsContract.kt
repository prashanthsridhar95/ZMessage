package com.zyke.message.presentation.settings

import com.zyke.message.domain.diagnostics.ParsingDiagnostics
import com.zyke.message.domain.model.AppSettings

object SettingsContract {

    data class State(
        val settings: AppSettings = AppSettings(),
        /** Null until the inbox has been read (or when SMS access is missing). */
        val diagnostics: ParsingDiagnostics? = null,
        val confirmingReset: Boolean = false
    )

    sealed interface Intent {
        /** Starts reading the inbox for diagnostics, if SMS access is granted. */
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class SetScreenProtection(val enabled: Boolean) : Intent
        data object AskReset : Intent
        data object CancelReset : Intent
        data object ConfirmReset : Intent
        data object CopyDiagnostics : Intent
    }

    sealed interface Effect {
        data class CopyToClipboard(val label: String, val text: String) : Effect
        data class ShowMessage(val text: String) : Effect
    }
}
