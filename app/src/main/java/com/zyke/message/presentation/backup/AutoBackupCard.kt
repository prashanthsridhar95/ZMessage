package com.zyke.message.presentation.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.data.backup.BackupFailure
import com.zyke.message.data.backup.BackupMechanism
import com.zyke.message.data.backup.BackupTrigger
import com.zyke.message.domain.backup.BackupCrypto
import com.zyke.message.domain.backup.BackupFrequency
import com.zyke.message.domain.backup.BackupSecret
import com.zyke.message.domain.backup.BackupText
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.TimeField
import com.zyke.message.presentation.common.ordinalDay
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.settings.PasswordField
import com.zyke.message.presentation.settings.ToggleRow
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: automatic backup of the person's setup, restored after a reinstall or on a new phone. Two independent
 * places, both off until switched on: Android's own Auto Backup of one encrypted file on this phone, and the person's
 * own Google Drive private app folder. Both hold the same PIN-encrypted file. The PIN is never stored; only a key
 * made from it, sealed in the phone's key store, so scheduled backups can run without asking.
 */
@Composable
internal fun AutoBackupCard() {
    val hub = rememberBackupHub()
    val host = LocalBackupHost.current
    val scope = rememberCoroutineScope()
    val view by hub.prefs.view.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(false) }
    var afterPin by remember { mutableStateOf<(() -> Unit)?>(null) }
    var restore by remember { mutableStateOf<RestoreSource?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var hasPhoneFile by remember { mutableStateOf(false) }
    LaunchedEffect(view.lastLocal, view.localEnabled) { hasPhoneFile = withContext(Dispatchers.IO) { hub.engine.localFile.isFile } }

    val zone = ZoneId.systemDefault()
    val now = remember(view) { Instant.now() }
    val schedule = view.schedule

    fun places(): Set<BackupMechanism> = buildSet {
        if (view.localEnabled) add(BackupMechanism.LOCAL)
        if (view.driveEnabled) add(BackupMechanism.DRIVE)
        if (view.folderEnabled) add(BackupMechanism.FOLDER)
    }
    fun changed() { hub.scheduler.rescheduleAll() }
    // The folder picker. The system keeps the grant for the chosen folder; nothing else is read from it.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            scope.launch {
                val kept = withContext(Dispatchers.IO) {
                    val previous = hub.prefs.folderTree
                    if (!hub.folder.takeAccess(tree)) {
                        false
                    } else {
                        if (previous != null && previous != tree.toString()) hub.folder.releaseAccess(previous)
                        hub.prefs.setFolder(tree.toString(), hub.folder.labelFor(tree))
                        hub.prefs.clearFailure(BackupMechanism.FOLDER)
                        true
                    }
                }
                if (kept) {
                    note = null
                    changed()
                    hub.runner.run(BackupTrigger.SETUP, setOf(BackupMechanism.FOLDER))
                } else {
                    note = "Android would not keep access to that folder. Please pick it again."
                }
            }
        }
    }
    fun openFolderPicker() { runCatching { pickFolder.launch(null) }.onFailure { note = "This phone has no folder picker." } }
    fun runNow(token: String? = null) {
        scope.launch { hub.runner.run(BackupTrigger.MANUAL, places(), token) }
    }
    /** Needs the key first: asks for a PIN, then carries on with [then]. */
    fun withKey(then: () -> Unit) {
        if (hub.keys.has()) then() else { afterPin = then; settingPin = true }
    }
    fun signInThenBackUp() {
        if (host == null) { note = "Google sign-in is not available on this phone."; return }
        host.signIn { token ->
            if (token == null) note = "Google sign-in was not completed." else { hub.prefs.clearFailure(BackupMechanism.DRIVE); runNow(token) }
        }
    }
    fun backUpNow() {
        note = null
        if (places().isEmpty()) { note = "Turn on a place to back up to, above."; return }
        withKey {
            val driveNeedsSignIn = view.driveEnabled && view.failure[BackupMechanism.DRIVE]?.failure.let { it == BackupFailure.AUTH_EXPIRED || it == BackupFailure.NOT_SIGNED_IN }
            if (driveNeedsSignIn) signInThenBackUp() else runNow()
        }
    }

    SectionCard("Automatic backup") {
        Caption(
            "Keep your setup safe so it comes back after you reinstall the app or move to a new phone: plan, limits, cards, goals, reminders, events, entries, names, categories, settings and the sender pictures you chose. Never your messages.",
            maxLines = 6
        )
        Caption(
            "Protected by a PIN only you know. It is not stored anywhere, and nobody can recover it for you.",
            Modifier.padding(top = 4.dp), maxLines = 3
        )

        val pinLine = if (view.keyReady) "PIN is set up" else "Choose a PIN to start"
        AppButton(
            text = if (view.keyReady) "Change PIN or passphrase" else "Choose a PIN",
            onClick = { afterPin = null; settingPin = true },
            style = ButtonStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        Caption(pinLine, Modifier.padding(top = 4.dp))

        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ToggleRow(
                title = "Back up to this phone's Android backup",
                description = "Keeps one encrypted file that Android's own backup uploads by itself, about daily when the phone is idle, charging and on Wi-Fi, and brings back when you reinstall or set up a new phone. We cannot control when Android uploads it; we only choose how often the file on this phone is refreshed (below). Needs no sign-in.",
                checked = view.localEnabled,
                onCheckedChange = { on ->
                    note = null
                    if (on) {
                        withKey { hub.prefs.setLocalEnabled(true); changed(); scope.launch { hub.runner.run(BackupTrigger.SETUP, setOf(BackupMechanism.LOCAL)) } }
                    } else {
                        hub.prefs.setLocalEnabled(false)
                        hub.prefs.clearFailure(BackupMechanism.LOCAL)
                        scope.launch(Dispatchers.IO) { hub.engine.deleteLocal() }
                        changed()
                        note = "Android may keep the last copy it already uploaded until it is replaced."
                    }
                }
            )
            ToggleRow(
                title = "Back up to Google Drive",
                description = "Uploads one file, encrypted with your PIN, to a private folder in your own Google Drive that only this app can see. Nothing else is sent: no messages, no analytics. Keeps the last 7. Off until you switch it on and sign in.",
                checked = view.driveEnabled,
                onCheckedChange = { on ->
                    note = null
                    if (on) {
                        withKey {
                            if (host == null) {
                                note = "Google sign-in is not available on this phone."
                            } else {
                                host.signIn { token ->
                                    if (token == null) {
                                        note = "Google sign-in was not completed, so Drive backup stays off."
                                    } else {
                                        hub.prefs.setDriveEnabled(true)
                                        changed()
                                        scope.launch { hub.runner.run(BackupTrigger.SETUP, setOf(BackupMechanism.DRIVE), token) }
                                    }
                                }
                            }
                        }
                    } else {
                        hub.prefs.setDriveEnabled(false)
                        hub.prefs.clearFailure(BackupMechanism.DRIVE)
                        changed()
                        note = "Backups already in your Drive stay there. You can remove them in Google Drive settings under Manage apps."
                    }
                }
            )
        }

        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(
                title = "Save a copy to a folder",
                description = "Also saves a dated file each time into a folder you choose, and keeps the newest 7. Needs no sign-in and no network. The files are encrypted with your PIN. Copy them anywhere; you need the PIN to open them.",
                checked = view.folderEnabled,
                onCheckedChange = { on ->
                    note = null
                    if (on) {
                        withKey { openFolderPicker() }
                    } else {
                        val old = hub.prefs.folderTree
                        hub.prefs.clearFolder()
                        hub.prefs.clearFailure(BackupMechanism.FOLDER)
                        scope.launch(Dispatchers.IO) { hub.folder.releaseAccess(old) }
                        changed()
                        note = "Files already saved in the folder stay there."
                    }
                }
            )
            if (view.folderEnabled) {
                Caption(
                    "Folder: ${view.folderLabel.ifEmpty { "the chosen folder" }}",
                    maxLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Change folder", ::openFolderPicker, Modifier.weight(1f), style = ButtonStyle.OUTLINE)
                    AppButton(
                        if (view.running) "Saving" else "Back up now",
                        { note = null; withKey { scope.launch { hub.runner.run(BackupTrigger.MANUAL, setOf(BackupMechanism.FOLDER)) } } },
                        Modifier.weight(1f), style = ButtonStyle.OUTLINE, enabled = !view.running
                    )
                }
            }
        }

        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FormGroup("How often") {
                Chips(
                    FREQUENCIES.map { (f, label) -> ChipOption(label, schedule.frequency == f) },
                    onSelect = { hub.prefs.setSchedule(schedule.copy(frequency = FREQUENCIES[it].first)); changed() }
                )
            }
            TimeField(
                label = "Around what time",
                time = schedule.timeOfDay,
                onPick = { picked -> if (picked != null) { hub.prefs.setSchedule(schedule.copy(timeOfDay = picked)); changed() } },
                supporting = "Around ${timeText(schedule.timeOfDay)}. Android may delay it a little to save battery."
            )
            if (schedule.frequency == BackupFrequency.WEEKLY || schedule.frequency == BackupFrequency.FORTNIGHTLY) {
                val names = DayOfWeek.values().map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
                PickField(
                    label = "On",
                    options = names,
                    selected = schedule.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    onPick = { picked -> hub.prefs.setSchedule(schedule.copy(dayOfWeek = DayOfWeek.values()[names.indexOf(picked).coerceAtLeast(0)])); changed() }
                )
            }
            if (schedule.frequency == BackupFrequency.MONTHLY) {
                PickField(
                    label = "On the",
                    options = (1..31).map { ordinalDay(it) },
                    selected = ordinalDay(schedule.dayOfMonth),
                    onPick = { picked -> hub.prefs.setSchedule(schedule.copy(dayOfMonth = picked.filter { it.isDigit() }.toIntOrNull() ?: 1)); changed() },
                    supporting = "In a shorter month it runs on the last day."
                )
            }
            ToggleRow(
                title = "Wi-Fi only",
                description = "Google Drive backups wait for Wi-Fi so they never use mobile data.",
                checked = view.wifiOnly,
                onCheckedChange = { hub.prefs.setWifiOnly(it); changed() }
            )
            ToggleRow(
                title = "Only while charging",
                description = "Wait until the phone is plugged in.",
                checked = view.chargingOnly,
                onCheckedChange = { hub.prefs.setChargingOnly(it); changed() }
            )
        }

        AppButton(
            text = if (view.running) "Backing up" else "Back up now",
            onClick = ::backUpNow,
            enabled = !view.running,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )

        // What happened, and what is next.
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in BackupMechanism.values()) {
                if (!view.enabled(m)) continue
                val last = view.result(m)
                val failure = view.failure[m]
                Caption(
                    when {
                        last == null -> "${m.label}: no backup yet"
                        m == BackupMechanism.FOLDER -> BackupText.lastSavedToFolder(last.at, now, zone, last.sizeBytes, view.folderLabel.ifEmpty { "the chosen folder" })
                        else -> BackupText.lastBackup(last.at, now, zone, last.sizeBytes, m.label)
                    },
                    maxLines = 2
                )
                if (failure != null && (last == null || failure.at.isAfter(last.at))) {
                    Caption("${m.label}: ${failure.failure.message}", color = MaterialTheme.colorScheme.error, maxLines = 3)
                    when (failure.failure) {
                        BackupFailure.AUTH_EXPIRED, BackupFailure.NOT_SIGNED_IN ->
                            AppButton("Sign in again", ::signInThenBackUp, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                        BackupFailure.NO_KEY ->
                            AppButton("Choose a PIN", { afterPin = null; settingPin = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                        BackupFailure.FOLDER_LOST ->
                            AppButton("Pick the folder again", ::openFolderPicker, style = ButtonStyle.TEXT)
                        else -> Unit
                    }
                }
            }
            val enabled = places()
            if (enabled.isEmpty()) {
                Caption("Automatic backup is off.")
            } else {
                val next = enabled.minOf { m -> schedule.nextAttempt(now, zone, view.lastSuccess(m), view.lastFailure(m)) }
                Caption(BackupText.nextBackup(next, now, zone), maxLines = 2)
            }
            if (view.picturesSkipped) {
                Caption("Your chosen sender pictures are over 20 MB together, so the last backup left them out. Your setup is safe.", maxLines = 3)
            }
            note?.let { Caption(it, maxLines = 4) }
        }

        AppButton(
            text = "Restore from Google Drive",
            onClick = { restore = RestoreSource.DRIVE },
            style = ButtonStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        if (hasPhoneFile) {
            AppButton(
                text = "Restore from this phone's backup",
                onClick = { restore = RestoreSource.PHONE },
                style = ButtonStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        Caption(
            "How safe is a 6-digit PIN? It has only a million possibilities. It keeps your backup private from anyone who just finds the file, but someone who gets the file itself could try every PIN on a fast computer. For real protection choose a long passphrase instead.",
            Modifier.padding(top = 12.dp), maxLines = 8
        )
    }

    if (settingPin) {
        PinSheet(
            onDismiss = { settingPin = false; afterPin = null },
            onSaved = {
                settingPin = false
                hub.prefs.refresh()
                val next = afterPin
                afterPin = null
                if (next != null) next() else if (places().isNotEmpty()) { changed(); runNow() }
            }
        )
    }
    restore?.let { RestoreFlow(it, { restore = null }) }
}

private val FREQUENCIES = listOf(
    BackupFrequency.DAILY to "Daily",
    BackupFrequency.WEEKLY to "Weekly",
    BackupFrequency.FORTNIGHTLY to "Every 2 weeks",
    BackupFrequency.MONTHLY to "Monthly"
)

private fun timeText(time: java.time.LocalTime): String =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(time).replace("AM", "am").replace("PM", "pm")

/** Choosing the PIN (or, as the advanced option, a passphrase). The secret is stretched once; only the key is kept. */
@Composable
private fun PinSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val hub = rememberBackupHub()
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val secret = first.toCharArray()
    val kind = BackupSecret.kindOf(secret)
    val acceptable = if (passphrase) kind == BackupSecret.Kind.PASSPHRASE else kind == BackupSecret.Kind.PIN
    val matches = first == second
    val obvious = !passphrase && BackupSecret.isObviousPin(secret)

    FormSheet(
        title = if (passphrase) "Choose a passphrase" else "Choose a 6-digit PIN",
        subtitle = "Needed to restore your backup on a new phone",
        onDismiss = onDismiss,
        primaryLabel = if (busy) "Saving" else "Save and back up",
        primaryEnabled = acceptable && matches && !busy,
        hint = when {
            !acceptable -> if (passphrase) "Use at least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters" else "Use exactly 6 digits"
            else -> "Type the same one again"
        },
        onPrimary = {
            busy = true
            failed = false
            val chars = first.toCharArray()
            scope.launch {
                val saved = withContext(Dispatchers.Default) {
                    runCatching {
                        val material = BackupCrypto.newKeyMaterial(chars)
                        hub.keys.save(material)
                        material.wipe()
                    }.isSuccess
                }
                chars.fill(' ')
                busy = false
                if (saved) { first = ""; second = ""; onSaved() } else failed = true
            }
        }
    ) {
        Caption(
            "It is not stored on this phone and cannot be recovered. If you forget it, your backups cannot be opened. Changing it only protects new backups; older ones keep the old PIN.",
            maxLines = 5
        )
        PasswordField(
            if (passphrase) "Passphrase" else "6-digit PIN", first, { first = if (passphrase) it else it.filter(Char::isDigit).take(BackupSecret.PIN_LENGTH) },
            error = if (obvious) "That PIN is easy to guess. You can still use it." else null,
            keyboardType = if (passphrase) KeyboardType.Password else KeyboardType.NumberPassword
        )
        PasswordField(
            "Again", second, { second = if (passphrase) it else it.filter(Char::isDigit).take(BackupSecret.PIN_LENGTH) },
            error = if (second.isNotEmpty() && !matches) "The two are different" else null,
            keyboardType = if (passphrase) KeyboardType.Password else KeyboardType.NumberPassword
        )
        if (failed) Caption("The PIN could not be saved on this phone. Please try again.", color = MaterialTheme.colorScheme.error, maxLines = 2)
        ToggleRow(
            title = "Advanced: use a passphrase instead",
            description = "Much stronger than 6 digits. A 6-digit PIN has only a million possibilities, so someone who gets the backup file could try them all offline.",
            checked = passphrase,
            onCheckedChange = { passphrase = it; first = ""; second = "" }
        )
    }
}
