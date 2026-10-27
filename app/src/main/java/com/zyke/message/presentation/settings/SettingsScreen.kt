package com.zyke.message.presentation.settings

import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.model.ThemeMode
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.settings.SettingsContract.Effect
import com.zyke.message.presentation.settings.SettingsContract.Intent
import com.zyke.message.presentation.settings.SettingsContract.State

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, versionName: String, onDismiss: () -> Unit) {
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
                is Effect.ShareReport -> runCatching { ReportSharing.share(context, effect.fileName, effect.text) }
                    .onFailure { Toast.makeText(context, R.string.report_failed, Toast.LENGTH_LONG).show() }
                is Effect.ShowMessage -> Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    SettingsSheet(state, versionName, viewModel::onIntent, onDismiss)
}

/** Settings as a bottom sheet over the current tab; as tall as its content, up to the screen. */
@Composable
fun SettingsSheet(
    state: State,
    versionName: String,
    onIntent: (Intent) -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 4.dp),
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )

            SectionCard("Appearance") {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.settings.themeMode == mode,
                                onClick = { onIntent(Intent.SetThemeMode(mode)) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = state.settings.themeMode == mode, onClick = null)
                        Text(mode.label, Modifier.padding(start = 12.dp))
                    }
                }
            }

            CardsSection(state.cards, onSave = { onIntent(Intent.SaveCard(it)) }, onDelete = { onIntent(Intent.DeleteCard(it)) })

            RcsSection()

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
                        else "${diagnostics.unrecognised} shown as plain text. Share a report as a file, with numbers, " +
                            "names and links masked, so they can be taught.",
                        Modifier.padding(top = 8.dp),
                        maxLines = 4
                    )
                    AppButton(
                        text = "Share report",
                        onClick = { onIntent(Intent.ShareDiagnostics) },
                        style = ButtonStyle.OUTLINE,
                        enabled = diagnostics.unrecognised > 0,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

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

            SectionCard("About") {
                Caption("Version $versionName")
                Caption("Plus Jakarta Sans, SIL Open Font License 1.1", Modifier.padding(top = 4.dp))
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

/** The person's cards: with their last four digits, messages are matched to the right card and grouped by cycle. */
@Composable
private fun CardsSection(cards: List<UserCard>, onSave: (UserCard) -> Unit, onDelete: (String) -> Unit) {
    var editing by remember { mutableStateOf<UserCard?>(null) }
    var adding by remember { mutableStateOf(false) }

    SectionCard("Your cards") {
        Caption(
            "Add the last four digits of your credit and debit cards so messages are matched to the right card. " +
                "For a credit card, add its statement date to see spends and payments by billing cycle.",
            maxLines = 5
        )
        cards.forEach { card ->
            Row(
                Modifier.fillMaxWidth().clickable { editing = card }.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    RowTitle(card.label)
                    Caption(
                        card.statementDay?.let { "${card.type.label} · statement on day $it" } ?: card.type.label
                    )
                }
                AppButton("Edit", { editing = card }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
        AppButton(
            text = "Add card",
            onClick = { adding = true },
            style = ButtonStyle.OUTLINE,
            modifier = Modifier.padding(top = 12.dp)
        )
    }

    if (adding) {
        CardDialog(initial = null, onSave = { onSave(it); adding = false }, onDelete = null, onDismiss = { adding = false })
    }
    editing?.let { card ->
        CardDialog(
            initial = card,
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(card.id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

/** RCS chats are not in the SMS provider; the only way to see them is the messaging app's notifications. */
@Composable
private fun RcsSection() {
    val context = LocalContext.current
    SectionCard("RCS messages") {
        Caption(
            "Chat (RCS) messages can't be read like SMS. If you allow notification access, new incoming " +
                "chats from Google Messages and Samsung Messages are added to the inbox. They stay on this phone.",
            maxLines = 5
        )
        AppButton(
            text = "Notification access",
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            style = ButtonStyle.OUTLINE,
            modifier = Modifier.padding(top = 12.dp)
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
