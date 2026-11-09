package com.zyke.message.presentation.trains

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.notifications.TrainTrackingControl
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/**
 * The switches for trains, under the flight ones on the same Settings page. Live train status is its own switch, off until chosen,
 * because it asks a different internet service than flights do; the notification while travelling is on unless turned off (with live
 * status on it follows the train; with it off you get one ordinary reminder). The lock-screen switch above is shared by both.
 */
@Composable
fun TrainSettingsSection() {
    val context = LocalContext.current
    val service = (context.applicationContext as ZykeMessagesApplication).container.trains
    val on by service.liveEnabled.collectAsStateWithLifecycle()
    val tracking by service.trackingEnabled.collectAsStateWithLifecycle()
    SectionCard("Live train status") {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).toggleable(value = on, onValueChange = { service.setLiveEnabled(it); TrainTrackingControl.rearm(context) }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Track trains on the day you travel")
                Caption(
                    "Asks an unofficial running-status service where your train is and how late. Only the train number (like 12633) and the day it started are sent, never your messages, PNR, stations or name.",
                    maxLines = 6
                )
            }
            Switch(checked = on, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).toggleable(value = tracking, onValueChange = { service.setTrackingEnabled(it); TrainTrackingControl.rearm(context) }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Notification while I travel")
                Caption(
                    "With live status on, an ongoing notification follows your train from your station to where you get off and updates every 30 seconds. When you arrive it becomes one you can dismiss. With live status off you get one ordinary reminder before you travel. At most two trips (flights and trains together) are followed at once.",
                    maxLines = 8
                )
            }
            Switch(checked = tracking, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
        }
        HorizontalDivider(Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Caption(
            "Off: the Home card still shows the times from your booking message, with no internet. The service is unofficial and may be slow, wrong or unavailable; then the card says so in one line and keeps your booking.",
            Modifier.padding(top = 12.dp), maxLines = 6
        )
        Caption(
            "To start: open the app up to half an hour before the train leaves your station, or tap the reminder the app leaves ten minutes before. Android does not let an app start this from the background by itself.",
            Modifier.padding(top = 8.dp), maxLines = 5
        )
        ManualTrainsSection()
    }
}
