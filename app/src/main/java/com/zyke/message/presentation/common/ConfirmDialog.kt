package com.zyke.message.presentation.common

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.theme.Spacing

/**
 * The one confirmation for something that cannot be undone (delete, remove, reset, clear, wipe). The body names what
 * will be lost, in a sentence. Cancel and the confirming button are the same width, side by side (stacked on a narrow
 * phone or at large text), and the confirming tap gives the firmer buzz (`haptics.reject()`) before [onConfirm] runs.
 * [destructive] false is for a choice that only needs a second look (changing the app icon): a normal filled button and
 * the light tick.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel",
    destructive: Boolean = true
) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text(body)
                ResponsiveRow(Modifier.padding(top = Spacing.xs), spacing = Spacing.sm) {
                    AppButton(cancelLabel, onDismiss, Modifier.cell(), style = ButtonStyle.OUTLINE)
                    AppButton(
                        confirmLabel,
                        { if (destructive) haptics.reject() else haptics.tick(); onConfirm() },
                        Modifier.cell(),
                        style = if (destructive) ButtonStyle.DANGER else ButtonStyle.PRIMARY
                    )
                }
            }
        },
        confirmButton = {}
    )
}
