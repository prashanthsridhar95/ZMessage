package com.zyke.message.presentation.email

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.email.ConnectResult
import com.zyke.message.domain.email.EmailAlertSenders
import com.zyke.message.domain.email.EmailAlertSettings
import com.zyke.message.domain.email.EmailInput
import com.zyke.message.domain.email.EmailProvider
import com.zyke.message.domain.email.EmailSync
import com.zyke.message.domain.email.EmailSyncEvery
import com.zyke.message.domain.email.SenderBasis
import com.zyke.message.domain.email.SyncOutcome
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.ConfirmDialog
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.settings.ToggleRow
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Settings, "Email alerts": the switch (with a one-time explanation), choosing the mail provider, the address and the APP PASSWORD, Connect,
 * the status of the last read with Sync now and Disconnect, how often the app may look, how long to keep alerts, the list of bank addresses that
 * are read, and deleting what was read. What is typed lives only in `remember` (never `rememberSaveable`, so it is not written to the saved
 * instance state) and the password is emptied the moment Connect succeeds. Nothing here talks to a mailbox: it asks [EmailSync], which asks the
 * app's one mail class. Not offered in Demo mode, which reads no mailbox.
 */
@Composable
internal fun EmailSettingsContent() {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val repo = container.emailAlerts
    val sync = container.emailSync
    val state by repo.state.collectAsStateWithLifecycle()
    val busy by sync.busy.collectAsStateWithLifecycle()
    val s = state.settings
    val demo = com.zyke.message.data.demo.DemoMode.active
    var explaining by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    FintechCard {
        RowTitle("Read bank alert emails")
        Caption(
            "Some banks are moving alerts from SMS to email (HDFC Bank stops UPI SMS alerts up to Rs.500 from 1 Jan 2027). With this on, the app reads " +
                "alert emails from banks, only on this phone, and they appear with your other messages marked Email.",
            Modifier.padding(top = Spacing.xs), maxLines = 8
        )
    }

    if (demo) {
        FintechCard {
            RowTitle("Demo mode")
            Caption("The demo never connects to a mailbox.", Modifier.padding(top = Spacing.xs), maxLines = 3)
        }
        return
    }

    FintechCard {
        ToggleRow(
            title = "Read bank alert emails",
            description = if (!s.enabled) "Off until you switch it on" else if (s.connected) "On" else "On, not connected yet",
            checked = s.enabled,
            onCheckedChange = { wanted -> if (!wanted) repo.update { it.copy(enabled = false) } else if (!s.explained) explaining = true else repo.update { it.copy(enabled = true) } }
        )
        if (s.enabled) Caption("Switching off pauses reading. Disconnect below forgets the password too.", Modifier.padding(top = Spacing.xs), maxLines = 3)
    }

    if (s.enabled && !s.connected) ConnectCard(s, sync)
    if (s.enabled && s.connected) {
        StatusCard(s, state.understood, state.notUnderstood, busy, sync, onDisconnect = { confirmDisconnect = true })
        FintechCard {
            Text("How often", Modifier.heading(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Caption("The app only looks when it is opened. There is no timer and no background service.", Modifier.padding(top = Spacing.xs, bottom = Spacing.sm), maxLines = 3)
            Chips(EmailSyncEvery.entries.map { ChipOption(it.label, it == s.syncEvery) }, onSelect = { i -> repo.update { it.copy(syncEvery = EmailSyncEvery.entries[i]) } })
        }
        FintechCard {
            Text("Keep alerts for", Modifier.heading(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Caption("Older alerts are removed from this phone on their own.", Modifier.padding(top = Spacing.xs, bottom = Spacing.sm), maxLines = 3)
            Chips(
                EmailAlertSettings.KEEP_CHOICES.map { m -> ChipOption("$m months", m == s.keepMonths) },
                onSelect = { i -> repo.update { it.copy(keepMonths = EmailAlertSettings.KEEP_CHOICES[i]) }; repo.prune(System.currentTimeMillis()) }
            )
        }
    }

    if (s.enabled) {
        FintechCard {
            RowTitle("Watched senders")
            Caption(
                "Only emails from exactly these addresses are looked for and read. Nothing else in your mailbox is ever fetched. The list is fixed in the app " +
                    "and has not been checked against real emails; an address marked unverified may be wrong.",
                Modifier.padding(top = Spacing.xs), maxLines = 6
            )
            Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                EmailAlertSenders.all.forEach { sender ->
                    Caption(sender.address + if (sender.basis == SenderBasis.UNVERIFIED) "  (unverified)" else "", maxLines = 2)
                }
            }
        }
        FintechCard {
            RowTitle("Delete email alerts from this phone")
            Caption(
                "Removes every alert read from email. Your mailbox is not touched. ${DisplayFormatters.count(state.alerts.size, "alert", "alerts")} on this phone.",
                Modifier.padding(top = Spacing.xs), maxLines = 3
            )
            AppButton("Delete email alerts", { confirmDelete = true }, Modifier.fillMaxWidth().padding(top = Spacing.md), style = ButtonStyle.DANGER, enabled = state.alerts.isNotEmpty())
        }
    }

    if (explaining) {
        AdaptiveSheet("Read bank alert emails?", { explaining = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("This lets the app read the alert emails your bank sends, so payments that no longer come by SMS are still counted.", style = MaterialTheme.typography.bodyLarge)
                EXPLANATION.forEach { Caption("• $it", maxLines = 6) }
                ResponsiveRow(Modifier.fillMaxWidth(), spacing = Spacing.sm) {
                    AppButton("Not now", { explaining = false }, Modifier.cell(), style = ButtonStyle.OUTLINE)
                    AppButton("Continue", { explaining = false; repo.update { it.copy(enabled = true, explained = true) } }, Modifier.cell())
                }
            }
        }
    }
    if (confirmDisconnect) {
        ConfirmDialog(
            title = "Disconnect email?",
            body = "The app password is forgotten and every alert read from email is removed from this phone. Your mailbox is not touched. Also delete the app password in your provider's account page if you no longer want it to exist.",
            confirmLabel = "Disconnect",
            onConfirm = { sync.disconnect(); confirmDisconnect = false },
            onDismiss = { confirmDisconnect = false }
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete email alerts?",
            body = "Every alert read from email is removed from this phone. The next sync reads your mailbox again.",
            confirmLabel = "Delete",
            onConfirm = { sync.deleteAlerts(); confirmDelete = false },
            onDismiss = { confirmDelete = false }
        )
    }
}

private val EXPLANATION = listOf(
    "It reads only emails from the bank addresses listed on this page. Every other email in your mailbox is never fetched.",
    "It is read-only: nothing is marked read, moved, flagged or deleted.",
    "Nothing leaves your phone except talking to your mail provider over a secure connection. The emails are read here and never sent anywhere.",
    "You sign in with an app password you make for this, not your own password. It is kept on this phone only, sealed with a key that cannot leave it, and it is never backed up.",
    "The wording of bank emails has not been checked against real ones. Something the app does not understand is shown, masked, under Messages not understood.",
    "You can disconnect at any time. That forgets the password and removes what was read."
)

/** The connect form, shared by Settings and the onboarding page ([onConnected] runs after a successful Connect, once the first read has been started). */
@Composable
internal fun ConnectCard(settings: EmailAlertSettings, sync: EmailSync, onConnected: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var providerId by remember { mutableStateOf(settings.providerId.ifEmpty { EmailProvider.GMAIL.id }) }
    var address by remember { mutableStateOf(settings.address) }
    var password by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val provider = EmailProvider.fromId(providerId) ?: EmailProvider.GMAIL
    val problem = if (address.isEmpty() && password.isEmpty()) null else EmailInput.problem(address, password)
    val canConnect = !working && problem == null
    // After an earlier password stopped working the address is already there; say why the form is back.
    val earlier = if (settings.address.isNotEmpty() && settings.lastOutcome != null && settings.lastOutcome != SyncOutcome.OK) EmailSync.sentenceOf(settings.lastOutcome) else null

    fun connect() {
        if (!canConnect) return
        working = true
        message = null
        scope.launch {
            val result = sync.connect(provider, address, password)
            working = false
            when (result) {
                is ConnectResult.Connected -> {
                    password = ""
                    shown = false
                    focus.clearFocus()
                    sync.syncNow()
                    onConnected()
                }
                is ConnectResult.Failed -> message = result.sentence
            }
        }
    }

    FintechCard {
        RowTitle("Connect your mailbox")
        Caption("Choose where your bank's emails arrive.", Modifier.padding(top = Spacing.xs), maxLines = 2)
        Chips(
            EmailProvider.entries.map { ChipOption(it.label, it == provider) },
            onSelect = { i -> providerId = EmailProvider.entries[i].id; message = null },
            modifier = Modifier.padding(top = Spacing.sm)
        )
        if (provider.caution.isNotEmpty()) Caption(provider.caution, Modifier.padding(top = Spacing.sm), color = MaterialTheme.colorScheme.error, maxLines = 6)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.take(EmailInput.MAX_PASSWORD + 60) },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 56.dp),
            label = { Text("Email address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(EmailInput.MAX_PASSWORD + 20) },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 56.dp),
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canConnect) connect() else focus.clearFocus() }),
            trailingIcon = {
                AppButton(
                    if (shown) "Hide" else "Show", { shown = !shown },
                    Modifier.semantics { contentDescription = if (shown) "Hide app password" else "Show app password" },
                    style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                )
            }
        )
        Caption(
            "Use an app password, never your normal password. Make one for ${provider.label}:",
            Modifier.padding(top = Spacing.md), maxLines = 3
        )
        provider.appPasswordSteps.forEach { Caption(it, Modifier.padding(top = Spacing.xs), maxLines = 4) }
        Caption("The app only reads alert emails from banks, and never anything else in your mailbox.", Modifier.padding(top = Spacing.md), maxLines = 3)
        (message ?: earlier)?.let { Text(it, Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (problem != null && message == null && password.isNotEmpty()) Text(problem, Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        AppButton(if (working) "Connecting..." else "Connect", ::connect, Modifier.fillMaxWidth().padding(top = Spacing.md), enabled = canConnect)
    }
}

@Composable
private fun StatusCard(
    s: EmailAlertSettings, understood: Int, notUnderstood: Int, busy: Boolean, sync: EmailSync, onDisconnect: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val provider = s.provider
    val failed = s.lastOutcome != null && s.lastOutcome != SyncOutcome.OK
    FintechCard {
        RowTitle(if (failed) "Connected, last read failed" else "Connected" + (provider?.let { " · ${it.label}" } ?: ""), color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Caption(s.address, Modifier.padding(top = Spacing.xs), maxLines = 1)
        Caption(
            if (s.lastSyncAt == 0L) "Not read yet" else "Last read " + DateUtils.getRelativeTimeSpanString(s.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            Modifier.padding(top = Spacing.xs), maxLines = 2
        )
        if (failed) Caption(EmailSync.sentenceOf(s.lastOutcome), Modifier.padding(top = Spacing.xs), color = MaterialTheme.colorScheme.error, maxLines = 4)
        Caption(
            "${DisplayFormatters.count(understood, "alert", "alerts")} read · $notUnderstood not understood",
            Modifier.padding(top = Spacing.xs), maxLines = 2
        )
        ResponsiveRow(Modifier.fillMaxWidth().padding(top = Spacing.md), spacing = Spacing.sm) {
            AppButton(if (busy) "Reading..." else "Sync now", { scope.launch { sync.syncNow() } }, Modifier.cell(), style = ButtonStyle.TONAL, enabled = !busy)
            AppButton("Disconnect", onDisconnect, Modifier.cell(), style = ButtonStyle.OUTLINE, enabled = !busy)
        }
    }
}
