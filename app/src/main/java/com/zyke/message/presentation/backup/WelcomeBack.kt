package com.zyke.message.presentation.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.backup.FirstRun
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.messages.components.Caption
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shown once on a fresh install, before the first screen: a calm "Welcome back" that offers to bring a setup back
 * from this phone's Android backup (when Android restored the file) or from the person's Google Drive, or to start
 * fresh. Nothing is restored without the PIN, and nothing is sent anywhere until the person picks Google Drive.
 */
@Composable
internal fun WelcomeBack(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // If the backup parts cannot be built for any reason, Welcome still works: it offers "Start fresh" only.
    val hub = remember { FirstRun.step<com.zyke.message.data.backup.BackupHub?>("welcome hub", null) { (context.applicationContext as ZykeMessagesApplication).container.backup } }
    androidx.compose.runtime.DisposableEffect(Unit) {
        FirstRun.welcomeOnScreen = true
        onDispose { FirstRun.welcomeOnScreen = false }
    }
    var phoneFileMade by remember { mutableStateOf<Long?>(null) }
    var restore by remember { mutableStateOf<RestoreSource?>(null) }
    var chosenFile by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickFile = rememberRestoreFilePicker { chosenFile = it; restore = RestoreSource.FILE }
    LaunchedEffect(Unit) {
        val h = hub ?: return@LaunchedEffect
        phoneFileMade = FirstRun.step("welcome look for phone backup", null) {
            withContext(Dispatchers.IO) { h.engine.readLocalOrNull()?.let { h.restorer.createdAt(it) } }
        }
    }
    fun startFresh() {
        // Remembered first, so nothing after it can bring this step back. The old backup file is not wanted any more;
        // it is removed (off the main thread) so it is not kept, or uploaded again, for nothing.
        FirstRun.markDone(context, "start fresh")
        scope.launch {
            FirstRun.step("welcome delete old backup file", Unit) { withContext(Dispatchers.IO) { hub?.engine?.deleteLocal() } }
            onFinished()
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Welcome back?",
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
        )
        Caption("If you used Zyke Message before, you can bring your setup back: plan, limits, cards, goals, reminders, events, names and settings. Your messages stay on the phone and are read again by themselves.", maxLines = 6)
        val made = phoneFileMade
        if (hub != null && made != null) {
            val whenMade = if (made > 0) " made " + DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(java.time.Instant.ofEpochMilli(made).atZone(ZoneId.systemDefault())) else ""
            Caption("Android brought back a backup$whenMade. You need the PIN you chose.", maxLines = 3)
            AppButton("Restore your setup from this phone's backup", { restore = RestoreSource.PHONE }, Modifier.fillMaxWidth())
        }
        if (hub != null) {
            AppButton(
                "Restore from Google Drive", { restore = RestoreSource.DRIVE }, Modifier.fillMaxWidth(),
                style = if (made != null) ButtonStyle.OUTLINE else ButtonStyle.PRIMARY
            )
            Caption("Signing in to Google only happens if you choose this. Only the app's own hidden folder in your Drive is read.", maxLines = 3)
            AppButton("Restore from a file", pickFile, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            Caption("A backup file you saved: from your backup folder, or an export. You need the PIN or passphrase.", maxLines = 3)
        }
        AppButton("Start fresh", ::startFresh, Modifier.fillMaxWidth(), style = if (hub == null) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE)
        if (made != null) Caption("Starting fresh also removes the old backup file from this phone.", maxLines = 2)
    }
    if (hub != null) restore?.let { RestoreFlow(it, { restore = null; chosenFile = null }, chosenFile) }
}
