package com.zyke.message.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zyke.message.data.backup.PrefsBackup
import com.zyke.message.domain.backup.BackupCrypto
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the person is in making a backup by hand. Restoring goes through the one shared restore flow. */
private sealed interface Stage {
    data object Idle : Stage
    data object MakingPassphrase : Stage
}

/**
 * Settings card for an encrypted copy of the person's own setup. Self-contained: it picks files with the system file
 * picker (no permission needed), does the slow key work off the main thread, and never keeps or logs a passphrase
 * beyond the moment it is used.
 */
@Composable
internal fun BackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<Stage>(Stage.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }

    fun reset() {
        stage = Stage.Idle
        pass = ""
        pass2 = ""
        error = null
        busy = false
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val secret = pass.toCharArray()
        pass = ""
        pass2 = ""
        if (uri == null || secret.size < BackupCrypto.MIN_PASSPHRASE_LENGTH) {
            if (uri != null) message = "The backup was not saved. Please try again."
            secret.fill(' ')
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.Default) {
                runCatching {
                    val file = BackupCrypto.encrypt(PrefsBackup.capture(context), secret)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            secret.fill(' ')
            busy = false
            message = if (saved) "Backup saved. Keep the passphrase safe: without it the file cannot be opened." else "The backup could not be saved."
        }
    }

    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickFile = com.zyke.message.presentation.backup.rememberRestoreFilePicker { restoreUri = it }

    SectionCard("Backup and restore") {
        Caption("An encrypted copy of your setup: plan, limits, cards, goals, reminders, events, entries you added, names, categories and settings.", maxLines = 4)
        Caption("Your messages are not in the backup; they stay on the phone. Sender pictures and what was read from messages are not included either; they come back by themselves.", Modifier.padding(top = 4.dp), maxLines = 5)
        AppButton(
            text = "Back up my setup",
            onClick = { message = null; error = null; pass = ""; pass2 = ""; stage = Stage.MakingPassphrase },
            style = ButtonStyle.OUTLINE,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
        AppButton(
            text = "Restore from a file",
            onClick = { message = null; pickFile() },
            style = ButtonStyle.OUTLINE,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        message?.let { Caption(it, Modifier.padding(top = 8.dp), maxLines = 4) }
    }

    when (stage) {
        Stage.Idle -> Unit
        Stage.MakingPassphrase -> {
            val tooShort = pass.length < BackupCrypto.MIN_PASSPHRASE_LENGTH
            val mismatch = pass2.isNotEmpty() && pass != pass2
            FormSheet(
                title = "Back up my setup",
                subtitle = "Protected by a passphrase only you know",
                onDismiss = { reset() },
                primaryLabel = "Choose where to save",
                primaryEnabled = !tooShort && pass == pass2,
                hint = if (tooShort) "Use at least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters" else "Type the same passphrase again",
                onPrimary = {
                    val day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT))
                    stage = Stage.Idle
                    saver.launch("zyke-backup-$day.zbk")
                }
            ) {
                Caption("There is no way to recover the passphrase. If you lose it, the backup cannot be opened.", maxLines = 3)
                PasswordField("Passphrase", pass, { pass = it }, error = null)
                PasswordField("Passphrase again", pass2, { pass2 = it }, error = if (mismatch) "The two passphrases are different" else null)
            }
        }
    }

    restoreUri?.let { uri ->
        com.zyke.message.presentation.backup.RestoreFlow(com.zyke.message.presentation.backup.RestoreSource.FILE, { restoreUri = null }, uri)
    }
}

/** A labelled field whose text is hidden as dots, for a passphrase. */
@Composable
internal fun PasswordField(
    label: String, value: String, onChange: (String) -> Unit, error: String?,
    keyboardType: KeyboardType = KeyboardType.Password
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
        )
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
