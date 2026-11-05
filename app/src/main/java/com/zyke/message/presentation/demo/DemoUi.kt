package com.zyke.message.presentation.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.data.demo.DemoSwitch
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption

/**
 * A slim line under the toolbar on every tab while Demo mode is on: it says plainly that what is shown is made up, and
 * offers the way out. Leaving asks first, so a stray tap in the middle of a demonstration cannot bring the real figures
 * on screen.
 */
@Composable
fun DemoBanner(modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(start = 16.dp, end = 6.dp)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Demo mode · sample data, not yours", Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        AppButton("Exit", { confirming = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
    if (confirming) ExitDemoDialog(onDismiss = { confirming = false }, onExit = { DemoSwitch.exit(context) })
}

@Composable
private fun ExitDemoDialog(onDismiss: () -> Unit, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exit demo mode?") },
        text = { Text("Your own messages and figures come back. The sample data goes away. Your app lock, if you use one, asks as usual.") },
        confirmButton = { AppButton("Exit demo mode", onExit, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
        dismissButton = { AppButton("Stay in demo", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
    )
}

/** Settings, Demo mode: what it is, and the one button to start or leave it. */
@Composable
fun DemoModeSettings() {
    val context = LocalContext.current
    val on = DemoMode.active
    var asking by rememberSaveable { mutableStateOf(false) }
    SectionCard(if (on) "Demo mode is on" else "Show the app with sample data") {
        Caption(
            "For showing Zyke Message to someone else. It fills the app with a made-up person's messages, accounts, cards, " +
                "bills and balances, so your own money and names stay out of sight.",
            maxLines = 5
        )
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Caption("• Your messages, setup and settings are not read, shown or changed.", maxLines = 3)
            Caption("• Anything you change in the demo is forgotten when you leave it.", maxLines = 3)
            Caption("• No notifications, reminders, backups or network requests while it is on.", maxLines = 3)
        }
        AppButton(
            text = if (on) "Exit demo mode" else "Start demo mode",
            onClick = { asking = true },
            style = if (on) ButtonStyle.OUTLINE else ButtonStyle.PRIMARY,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
        )
        Caption(
            if (on) "The app restarts with your own data." else "The app restarts, and again when you leave. You can also start it from the app icon: press and hold it, then choose Demo mode.",
            Modifier.padding(top = 6.dp), maxLines = 4
        )
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(if (on) "Exit demo mode?" else "Start demo mode?", Modifier.semantics { heading() }) },
            text = {
                Text(
                    if (on) "Your own messages and figures come back. The sample data goes away."
                    else "The app restarts with sample data. Your own data stays exactly as it is, and comes back when you exit."
                )
            },
            confirmButton = {
                AppButton(
                    if (on) "Exit" else "Start", { asking = false; if (on) DemoSwitch.exit(context) else DemoSwitch.enter(context) },
                    style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                )
            },
            dismissButton = { AppButton("Cancel", { asking = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

/** What stands in place of the backup cards while Demo mode is on: the demo is never backed up or restored over. */
@Composable
fun DemoBackupNote() {
    SectionCard("Backup") {
        Caption("Backup and restore are off in demo mode. The sample data is never saved, and a backup file can't replace it.", maxLines = 4)
    }
}
