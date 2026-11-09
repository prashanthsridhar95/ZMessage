package com.zyke.message.presentation.flights

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.notifications.FlightTrackingControl
import com.zyke.message.notifications.LiveUpdates
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/**
 * The switches for flights: live status (off until chosen, because it asks the internet), the notification while flying (on
 * unless turned off; it needs live status to be an "in the air" notification), and the flights you added by hand.
 */
@Composable
fun FlightSettingsSection() {
    val service = LocalContext.current.flightService()
    val context = LocalContext.current
    val on by service.liveEnabled.collectAsStateWithLifecycle()
    val tracking by service.trackingEnabled.collectAsStateWithLifecycle()
    val lockDetails by service.lockScreenDetails.collectAsStateWithLifecycle()
    SectionCard("Live flight status") {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).toggleable(value = on, onValueChange = { service.setLiveEnabled(it); FlightTrackingControl.rearm(context) }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Track flights on the day you fly")
                Caption(
                    "Asks Flightradar24 for delays, gate and landing. Only the airline code and flight number (like 6E 2345) are sent, never your messages, PNR or name.",
                    maxLines = 5
                )
            }
            Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).toggleable(value = tracking, onValueChange = { service.setTrackingEnabled(it); FlightTrackingControl.rearm(context) }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Notification while I fly")
                Caption(
                    "With live status on, an ongoing notification follows your flight in the air and updates every 30 seconds (the internet is asked that often only then). When you land it becomes one you can dismiss. With live status off you get one ordinary reminder before you fly.",
                    maxLines = 6
                )
            }
            Switch(checked = tracking, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).toggleable(value = lockDetails, onValueChange = { service.setLockScreenDetails(it) }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Show flight details on the lock screen")
                Caption(
                    "On: the route, landing time and countdown show on the lock screen. Off: only “Flight in progress” until you unlock. Applies from the next update, within 30 seconds.",
                    maxLines = 5
                )
            }
            Switch(checked = lockDetails, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
        }
        LockScreenHealth(lockDetails)
        HorizontalDivider(Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Caption(
            "Off: the Home card still shows the times from your booking message, with no internet. The service is unofficial and may be slow or unavailable.",
            Modifier.padding(top = 12.dp), maxLines = 4
        )
        Caption(
            "To start: open the app up to half an hour before you fly, or tap the reminder the app leaves ten minutes before departure. Android does not let an app start this from the background by itself.",
            Modifier.padding(top = 8.dp), maxLines = 5
        )
        ManualFlightsSection()
    }
}

/**
 * Two read-only hints under the lock-screen switch, each with the button that fixes it: Live Updates switched off for the app in
 * the system settings (Android 16 only), and the phone's own "hide sensitive content on the lock screen" which wins over the app.
 * They look again each time the person comes back from the system settings.
 */
@Composable
private fun LockScreenHealth(wantsDetails: Boolean) {
    val context = LocalContext.current
    var recheck by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) recheck += 1 }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }
    val liveUpdatesOff = remember(recheck) { LiveUpdates.canPost(context) == false }
    val systemHides = remember(recheck) { LiveUpdates.lockScreenHidesContent(context) }
    if (wantsDetails && liveUpdatesOff) {
        Caption("Live updates are off for Zyke in system settings, so the flight shows as an ordinary notification.", Modifier.padding(top = 8.dp), maxLines = 3)
        AppButton("Open live update settings", { LiveUpdates.openSettings(context) }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
    }
    if (wantsDetails && systemHides) {
        Caption("Your phone hides notification content on the lock screen, so the flight may show there as “Sensitive content hidden”.", Modifier.padding(top = 8.dp), maxLines = 3)
        AppButton("Open lock screen settings", { LiveUpdates.openLockScreenSettings(context) }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
    }
}
