package com.zyke.message.presentation.settings

import androidx.compose.ui.text.font.FontWeight
import com.zyke.message.presentation.dashboard.components.LocalSectionsAsSheets
import androidx.compose.runtime.CompositionLocalProvider
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    AppBottomSheet(onDismiss, contentInset = 16.dp) {
        // Each section is a tile here that opens its own sheet, so Settings is a short list to choose from.
        CompositionLocalProvider(LocalSectionsAsSheets provides true) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 4.dp).heading(),
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )

            GroupLabel("Money")
            PlanSection(
                plan = state.plan,
                spotted = state.spottedSalary,
                onSalary = { amount, day -> onIntent(Intent.SetSalary(amount, day)) },
                onSavingsGoal = { onIntent(Intent.SetSavingsGoal(it)) },
                onSaveItem = { onIntent(Intent.SavePlanItem(it)) },
                onDeleteItem = { onIntent(Intent.DeletePlanItem(it)) }
            )

            CycleSection(
                plan = state.plan,
                spotted = state.spottedSalary,
                current = state.cycleRange,
                onChange = { mode, day -> onIntent(Intent.SetCycle(mode, day)) }
            )

            LimitsSection(
                limits = state.limits,
                onSave = { category, daily, monthly -> onIntent(Intent.SetLimits(category, daily, monthly)) },
                onRemove = { onIntent(Intent.RemoveLimits(it)) }
            )

            WishlistSection(
                items = state.wishlist,
                onSave = { onIntent(Intent.SaveWish(it)) },
                onRemove = { onIntent(Intent.RemoveWish(it)) }
            )

            CardsSection(state.cards, onSave = { onIntent(Intent.SaveCard(it)) }, onDelete = { onIntent(Intent.DeleteCard(it)) })

            GroupLabel("Messages")
            SectionCard("Pictures") {
                Caption(
                    "Pick one from any message sheet",
                    maxLines = 3
                )
                ToggleRow(
                    title = "Fetch brand logos from the internet",
                    description = "Looks up logos of well-known brands once each. Only the brand name is sent.",
                    checked = state.settings.fetchLogos,
                    onCheckedChange = { onIntent(Intent.SetFetchLogos(it)) },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            RcsSection()

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

            GroupLabel("Privacy and alerts")
            SectionCard("Privacy", summary = if (state.settings.appLock) "App lock on" else "Private on this phone") {
                Caption(
                    "Messages stay on this phone",
                    maxLines = 5
                )
                ToggleRow(
                    title = "Block screenshots",
                    description = "Also hides the recent-apps view",
                    checked = state.settings.screenProtection,
                    onCheckedChange = { onIntent(Intent.SetScreenProtection(it)) },
                    modifier = Modifier.padding(top = 12.dp)
                )
                AppLockToggle(
                    enabled = state.settings.appLock,
                    onChange = { onIntent(Intent.SetAppLock(it)) },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            SectionCard("Reminders", summary = if (state.settings.morningDigest) "Morning summary on" else "Off") {
                MorningDigestToggle(
                    enabled = state.settings.morningDigest,
                    onChange = { onIntent(Intent.SetMorningDigest(it)) }
                )
                LiveAlertsToggle(
                    enabled = state.settings.liveAlerts,
                    onChange = { onIntent(Intent.SetLiveAlerts(it)) },
                    modifier = Modifier.padding(top = 12.dp)
                )
                Caption(
                    "A \"Your day\" widget is in the widget list",
                    Modifier.padding(top = 12.dp),
                    maxLines = 2
                )
            }

            GroupLabel("App")
            SectionCard("Appearance", summary = state.settings.themeMode.label) {
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

            SectionCard("Your data") {
                Caption(
                    "Clears marks and budgets, never your messages",
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

    SectionCard("Your cards & accounts") {
        Caption(
            "Last four digits, bank name and statement day",
            maxLines = 5
        )
        cards.forEach { card ->
            Row(
                Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Edit ${card.label}") { editing = card }.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    RowTitle(card.label)
                    Caption(
                        listOfNotNull(
                            card.type.label,
                            card.bankName.takeIf { it.isNotBlank() && card.name.isNotBlank() },
                            card.statementDay?.let { "statement on day $it" }
                        ).joinToString(" · ")
                    )
                }
                AppButton("Edit", { editing = card }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
        AppButton(
            text = "Add card or account",
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

/** Ask for the phone's fingerprint, face or screen lock when coming back to the app; needs a screen lock to exist. */
@Composable
private fun AppLockToggle(enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasScreenLock = remember {
        (context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
    }
    ToggleRow(
        title = "Lock the app",
        description = if (hasScreenLock) {
            "Asks for your screen lock on return"
        } else {
            "Needs a screen lock on the phone"
        },
        checked = enabled && hasScreenLock,
        onCheckedChange = { if (hasScreenLock) onChange(it) },
        modifier = modifier
    )
}

/** The morning digest needs permission to show notifications on newer Android, asked for only when it is switched on. */
@Composable
private fun MorningDigestToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onChange(granted) }
    ToggleRow(
        title = "Morning digest",
        description = "A short summary at about 8 AM",
        checked = enabled,
        onCheckedChange = { wanted ->
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            when {
                !wanted -> onChange(false)
                allowed -> onChange(true)
                else -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )
}

/**
 * "Copy code" and "Pay" buttons the moment a code or card bill arrives. It needs permission to hear about new
 * texts and to show notifications, and both are asked for only when this is switched on.
 */
@Composable
private fun LiveAlertsToggle(enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permissions = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        onChange(permissions.all { results[it] == true })
    }
    ToggleRow(
        title = "Copy-code and Pay buttons",
        description = "A Copy code or Pay button the moment one arrives",
        checked = enabled,
        onCheckedChange = { wanted ->
            val allowed = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            when {
                !wanted -> onChange(false)
                allowed -> onChange(true)
                else -> launcher.launch(permissions)
            }
        },
        modifier = modifier
    )
}

/** RCS chats are not in the SMS provider; the only way to see them is the messaging app's notifications. */
@Composable
private fun RcsSection() {
    val context = LocalContext.current
    SectionCard("RCS messages") {
        // Whether the reader is on, and what it has caught, so a missing top-up can be traced.
        val container = (context.applicationContext as com.zyke.message.ZykeMessagesApplication).container
        val enabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val topUps = androidx.compose.runtime.remember { container.rcsStore.all().count { com.zyke.message.domain.classification.UpiLiteNotification.isFromNotification(it.sender) } }
        Caption(
            (if (enabled) "Notification access is on" else "Notification access is off") +
                " · $topUps UPI Lite top-up${if (topUps == 1) "" else "s"} caught from Google Pay",
            maxLines = 3
        )
        Caption(
            "Adds new chats from Google and Samsung Messages, and UPI Lite top-ups from Google Pay so they count as spending",
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
            Caption(description, maxLines = 6)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A small heading over a group of settings tiles. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = 12.dp).heading(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary
    )
}
