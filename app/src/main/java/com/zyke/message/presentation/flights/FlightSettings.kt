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
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/** The one switch for live flight status. Off until chosen, because it asks the internet. */
@Composable
fun FlightSettingsSection() {
    val service = LocalContext.current.flightService()
    val on by service.liveEnabled.collectAsStateWithLifecycle()
    SectionCard("Live flight status") {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).toggleable(value = on, onValueChange = { service.setLiveEnabled(it) }, role = Role.Switch),
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
        Caption(
            "Off: the Home card still shows the times from your booking message, with no internet. The service is unofficial and may be slow or unavailable.",
            Modifier.padding(top = 12.dp), maxLines = 5
        )
    }
}
