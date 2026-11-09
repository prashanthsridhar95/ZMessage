package com.zyke.message.presentation.flights

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.notifications.FlightTrackingControl
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
    SectionCard("Live flight status") {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).toggleable(value = on, onValueChange = { service.setLiveEnabled(it); FlightTrackingControl.rearm(context) }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Track flights on the day you fly")
                Caption(
                    "Asks Flightradar24 for delays, gate and landing. Only the airline code and flight number (like 6E 2345) are sent, never your messages, PNR or name.",
                    maxLines = 6
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
                    "With live status on, a notification you can’t swipe away shows your flight while it is in the air and updates every 30 seconds (the app asks the internet that often only then). It ends when you land, leaving a notification you can dismiss. “Stop tracking” on it ends it sooner. With live status off you get one ordinary reminder before you fly.",
                    maxLines = 9
                )
            }
            Switch(checked = tracking, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
        }
        Caption(
            "Off: the Home card still shows the times from your booking message, with no internet. The service is unofficial and may be slow or unavailable.",
            Modifier.padding(top = 12.dp), maxLines = 5
        )
        Caption(
            "Starting: open the app up to half an hour before you fly, or tap the notification the app leaves ten minutes before departure. On Android 12 and later a phone does not let an app start this from the background by itself.",
            Modifier.padding(top = 8.dp), maxLines = 6
        )
        ManualFlightsSection()
    }
}
