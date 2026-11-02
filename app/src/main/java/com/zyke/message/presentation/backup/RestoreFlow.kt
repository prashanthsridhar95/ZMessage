package com.zyke.message.presentation.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.data.backup.BackupFailureException
import com.zyke.message.data.backup.ChosenFile
import com.zyke.message.data.diag.DevLog
import com.zyke.message.data.backup.PreparedRestore
import com.zyke.message.data.backup.PrefsBackup
import com.zyke.message.domain.backup.BackupException
import com.zyke.message.domain.backup.BackupText
import com.zyke.message.domain.backup.RemoteBackup
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.settings.PasswordField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one "Restore from a file" picker used wherever restore is offered. A `.zbk` has no registered mime type, so any
 * file can be chosen; the app looks inside it ([RestoreFlow]) before it asks for the PIN. Returns the function that opens the picker.
 */
@Composable
internal fun rememberRestoreFilePicker(onPicked: (android.net.Uri) -> Unit): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPicked(uri) }
    return { runCatching { launcher.launch(arrayOf("*/*")) } }
}

internal enum class RestoreSource { PHONE, DRIVE, FILE }

private sealed interface Step {
    data object Working : Step
    data class Choosing(val items: List<RemoteBackup>, val token: String?) : Step
    data class EnterPin(val file: String, val madeAt: Long) : Step
    data class Confirm(val prepared: PreparedRestore) : Step
    data object Restored : Step
    data class Failed(val message: String) : Step
}

/**
 * Bringing a backup back, from this phone's Android backup file, from Google Drive or from a file the person chose
 * (a manual export, a `.zbk` from the backup folder, the `.bin`): find it, ask for the PIN (or
 * passphrase), check it, and ask before replacing anything that is already set up. Shared by the Welcome step and
 * Settings. Slow work (key stretching, downloads) runs off the main thread; a wrong PIN waits a second before it says so.
 */
@Composable
internal fun RestoreFlow(source: RestoreSource, onClose: () -> Unit, fileUri: android.net.Uri? = null) {
    val context = LocalContext.current
    val hub = rememberBackupHub()
    val host = LocalBackupHost.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Working) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun fail(message: String) { step = Step.Failed(message) }

    LaunchedEffect(source, fileUri) {
        try {
            when (source) {
                RestoreSource.PHONE -> {
                    val found = withContext(Dispatchers.IO) { hub.engine.readLocalOrNull()?.let { it to hub.restorer.createdAt(it) } }
                    val made = found?.second
                    if (found == null || made == null) fail("No backup was found on this phone.") else step = Step.EnterPin(found.first, made)
                }
                RestoreSource.FILE -> {
                    val uri = fileUri
                    if (uri == null) {
                        fail("No file was chosen.")
                    } else {
                        // Size cap and header check happen before the PIN is asked for.
                        when (val read = withContext(Dispatchers.IO) { hub.restorer.readChosenFile(uri) }) {
                            is ChosenFile.Rejected -> fail(read.message)
                            is ChosenFile.Text -> {
                                val made = withContext(Dispatchers.Default) { hub.restorer.createdAt(read.text) }
                                if (made == null) fail("This doesn't look like a Zyke backup.") else step = Step.EnterPin(read.text, made)
                            }
                        }
                    }
                }
                RestoreSource.DRIVE -> {
                    if (host == null) { fail("Google sign-in is not available here."); return@LaunchedEffect }
                    host.signIn { token ->
                        if (token == null) {
                            fail("Google sign-in was not completed.")
                        } else {
                            scope.launch {
                                try {
                                    val items = hub.drive?.list(token).orEmpty()
                                    step = if (items.isEmpty()) Step.Failed("No backups were found in your Google Drive.") else Step.Choosing(items, token)
                                } catch (e: BackupFailureException) {
                                    fail(e.failure.message)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    fail("The backup could not be found.")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DevLog.d("backup") { "restore step failed class=${e.javaClass.simpleName}" }
            fail("The backup could not be opened.")
        }
    }

    when (val s = step) {
        Step.Working -> AlertDialog(
            onDismissRequest = onClose,
            confirmButton = { AppButton("Cancel", onClose, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            title = { Text("One moment") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Looking for your backup")
                }
            }
        )
        is Step.Choosing -> AdaptiveSheet(title = "Choose a backup", subtitle = "Newest first", onDismiss = onClose, contentSpacing = 8.dp) {
            val zone = ZoneId.systemDefault()
            s.items.forEach { item ->
                key(item.id) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(role = Role.Button, onClickLabel = "Use this backup") {
                                step = Step.Working
                                scope.launch {
                                    try {
                                        val text = hub.drive!!.download(item.id, s.token)
                                        val made = hub.restorer.createdAt(text)
                                        step = if (made == null) Step.Failed("That file is not a Zyke backup.") else Step.EnterPin(text, made)
                                    } catch (e: BackupFailureException) {
                                        fail(e.failure.message)
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        fail("The backup could not be downloaded.")
                                    }
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(item.modifiedAt.atZone(zone)),
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                            )
                            Caption(BackupText.size(item.sizeBytes))
                        }
                    }
                }
            }
        }
        is Step.EnterPin -> FormSheet(
            title = "Enter your PIN",
            subtitle = if (s.madeAt > 0) "Backup made " + DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(s.madeAt).atZone(ZoneId.systemDefault())) else null,
            onDismiss = onClose,
            primaryLabel = if (busy) "Opening" else "Restore",
            primaryEnabled = pin.isNotEmpty() && !busy,
            hint = "Type the 6-digit PIN (or passphrase) you chose when you set up the backup",
            onPrimary = {
                val secret = pin.toCharArray()
                busy = true
                error = null
                scope.launch {
                    try {
                        val prepared = hub.restorer.prepare(s.file, secret)
                        pin = ""
                        // Never silently overwrite: ask first when there is already a setup on this phone.
                        val existing = withContext(Dispatchers.IO) { PrefsBackup.hasSetupData(context) }
                        if (existing) {
                            step = Step.Confirm(prepared)
                        } else {
                            val applied = withContext(Dispatchers.Default) { runCatching { hub.restorer.apply(prepared) }.isSuccess }
                            if (applied) step = Step.Restored else fail("The backup could not be restored.")
                        }
                    } catch (e: BackupException.WrongPassphrase) {
                        error = "That PIN or passphrase does not open this backup, or the file has been changed. Try again."
                    } catch (e: BackupException) {
                        error = e.reason
                    } catch (e: PrefsBackup.InvalidSnapshotException) {
                        error = "This backup does not contain a usable setup."
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DevLog.d("backup") { "restore open failed class=${e.javaClass.simpleName}" }
                        error = "That backup could not be opened."
                    } finally {
                        secret.fill(' ')
                        busy = false
                    }
                }
            }
        ) {
            Caption("Your PIN is not stored anywhere, so only you can open this backup.", maxLines = 3)
            PasswordField("PIN or passphrase", pin, { pin = it; error = null }, error = error)
        }
        is Step.Confirm -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Replace what you have now?") },
            text = {
                Text(
                    "This replaces your current plan, limits, cards, goals, reminders, events, entries and settings" +
                        (if (s.prepared.pictureCount > 0) " and your chosen sender pictures" else "") +
                        " with the backup. Your messages are not touched. The app will close and you open it again."
                )
            },
            confirmButton = {
                AppButton("Replace my setup", {
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { runCatching { hub.restorer.apply(s.prepared) }.isSuccess }
                        // A restored phone is not new here: the tour is not replayed.
                        if (ok) { com.zyke.message.data.repository.TourSeenRepositoryImpl.markRestored(context); step = Step.Restored } else fail("The backup could not be restored.")
                    }
                }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            },
            dismissButton = { AppButton("Cancel", onClose, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
        Step.Restored -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Backup restored") },
            text = { Text("The app closes now so it can load your restored setup. Open it again to continue. Backups to Google Drive need you to sign in again.") },
            confirmButton = {
                AppButton("Close the app", {
                    // Closed at once, so nothing still in memory writes the old setup back.
                    (context as? android.app.Activity)?.finishAndRemoveTask()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        )
        is Step.Failed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Could not restore") },
            text = { Text(s.message) },
            confirmButton = { AppButton("OK", onClose, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}
