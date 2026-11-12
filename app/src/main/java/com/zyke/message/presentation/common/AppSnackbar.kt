package com.zyke.message.presentation.common

import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.AppShapes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zyke.message.domain.privacy.AmountMasker
import androidx.compose.runtime.staticCompositionLocalOf

/** The app's one snackbar, drawn at the top of everything, in front of any sheet. */
val LocalAppSnackbar = staticCompositionLocalOf { SnackbarHostState() }

private class MaskedSnackbarData(private val data: SnackbarData) : SnackbarData {
    override val visuals: SnackbarVisuals = object : SnackbarVisuals {
        override val message: String = AmountMasker.mask(data.visuals.message)
        override val actionLabel: String? = data.visuals.actionLabel
        override val withDismissAction: Boolean = data.visuals.withDismissAction
        override val duration: SnackbarDuration = data.visuals.duration
    }
    override fun performAction() = data.performAction()
    override fun dismiss() = data.dismiss()
}

/** The snackbar host, with amounts in its messages ("Removed ₹500") shown as dots while amounts are hidden. */
@Composable
fun PrivateSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val hidden = LocalPrivacyMode.current.hidden
    // One shape for every message: the control radius, with the gutter around it.
    SnackbarHost(state, modifier.padding(horizontal = Spacing.lg)) { data ->
        Snackbar(if (hidden) MaskedSnackbarData(data) else data, shape = AppShapes.control)
    }
}
