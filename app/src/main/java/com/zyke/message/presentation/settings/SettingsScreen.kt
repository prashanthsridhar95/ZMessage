package com.zyke.message.presentation.settings

import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.settings.SettingsContract.Effect
import com.zyke.message.presentation.settings.SettingsContract.Intent
import com.zyke.message.presentation.settings.SettingsContract.State

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, versionName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onIntent(Intent.ScreenStarted(granted))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is Effect.CopyToClipboard -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
                    Toast.makeText(context, R.string.report_copied, Toast.LENGTH_LONG).show()
                }
                is Effect.ShowMessage -> Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    SettingsScreen(state, versionName, viewModel::onIntent, onBack, modifier)
}

@Composable
fun SettingsScreen(
    state: State,
    versionName: String,
    onIntent: (Intent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clickable(onClickLabel = "Back", onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    modifier = Modifier.size(24.dp),
                    painter = painterResource(R.drawable.baseline_arrow_back_24),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    contentDescription = "Back"
                )
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "privacy") {
                SectionCard("Privacy") {
                    Caption(
                        "Messages are read and sorted on this phone only. The app has no internet permission, " +
                            "so nothing can be uploaded, and backups of its data are switched off.",
                        maxLines = 5
                    )
                    ToggleRow(
                        title = "Block screenshots",
                        description = "Also hides the app's content in the recent-apps view",
                        checked = state.settings.screenProtection,
                        onCheckedChange = { onIntent(Intent.SetScreenProtection(it)) },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            item(key = "parsing") {
                SectionCard("Parsing health") {
                    val diagnostics = state.diagnostics
                    if (diagnostics == null) {
                        Caption("Allow SMS access to see how well your messages are understood.", maxLines = 3)
                    } else if (diagnostics.moneyMessages == 0) {
                        Caption("No money messages found yet.")
                    } else {
                        val fraction = diagnostics.recognised.toFloat() / diagnostics.moneyMessages
                        RowTitle("${diagnostics.recognised} of ${diagnostics.moneyMessages} money messages understood")
                        ShareBar(fraction)
                        Caption(
                            if (diagnostics.unrecognised == 0) "Everything was understood."
                            else "${diagnostics.unrecognised} shown as plain text. Copy a report with numbers, " +
                                "names and links masked, so they can be taught.",
                            Modifier.padding(top = 8.dp),
                            maxLines = 4
                        )
                        AppButton(
                            text = "Copy report",
                            onClick = { onIntent(Intent.CopyDiagnostics) },
                            style = ButtonStyle.OUTLINE,
                            enabled = diagnostics.unrecognised > 0,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }

            item(key = "data") {
                SectionCard("Your data") {
                    Caption(
                        "Clear what the app remembers: read, pinned and archived marks, dismissed tips and budgets. " +
                            "Your messages are never touched.",
                        maxLines = 4
                    )
                    AppButton(
                        text = "Reset marks and budgets",
                        onClick = { onIntent(Intent.AskReset) },
                        style = ButtonStyle.OUTLINE,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            item(key = "about") {
                SectionCard("About") {
                    Caption("Version $versionName")
                    Caption("Plus Jakarta Sans, SIL Open Font License 1.1", Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (state.confirmingReset) {
        AlertDialog(
            onDismissRequest = { onIntent(Intent.CancelReset) },
            title = { Text("Reset marks and budgets?") },
            text = { Text("This clears read, pinned and archived marks, dismissed tips and all budgets. It can't be undone.") },
            confirmButton = { AppButton("Reset", { onIntent(Intent.ConfirmReset) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            dismissButton = { AppButton("Cancel", { onIntent(Intent.CancelReset) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            Caption(description, maxLines = 2)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}
