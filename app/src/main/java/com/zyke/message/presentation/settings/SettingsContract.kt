package com.zyke.message.presentation.settings

import com.zyke.message.domain.diagnostics.ParsingDiagnostics
import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.model.ThemeMode
import com.zyke.message.domain.model.UserCard

object SettingsContract {

    data class State(
        val settings: AppSettings = AppSettings(),
        /** Null until the inbox has been read (or when SMS access is missing). */
        val diagnostics: ParsingDiagnostics? = null,
        val confirmingReset: Boolean = false,
        val cards: List<UserCard> = emptyList()
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
        data class SaveCard(val card: UserCard) : Intent
        data class DeleteCard(val id: String) : Intent
        data object AskReset : Intent
        data object CancelReset : Intent
        data object ConfirmReset : Intent
        data object ShareDiagnostics : Intent
    }

    sealed interface Effect {
        /** Write [text] to a file named [fileName] and offer it to the apps that can receive it. */
        data class ShareReport(val fileName: String, val text: String) : Effect
        data class ShowMessage(val text: String) : Effect
    }
}
