package com.zyke.message.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.data.diag.DevLog
import com.zyke.message.data.perf.LowEnd
import com.zyke.message.domain.device.DeviceProfile
import com.zyke.message.domain.model.LiteMode
import com.zyke.message.domain.model.PerformanceMode
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.common.ChoiceCards
import com.zyke.message.presentation.common.SegmentedTabs
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption

/** Settings, Appearance: how much motion the app spends. Automatic goes lite on phones with little memory. */
@Composable
internal fun PerformanceCard(selected: PerformanceMode, onSelect: (PerformanceMode) -> Unit) {
    val lowEnd by LowEnd.detected.collectAsStateWithLifecycle()
    SectionCard("Performance", subtitle = "Smooth animations") {
        ChoiceCards(
            choices = PerformanceMode.entries.map { Choice(it, it.label, it.description) },
            selected = selected,
            onSelect = onSelect
        )
        val lite = LiteMode.resolve(selected, lowEnd)
        Caption(
            "This phone: " + (if (lowEnd) "little memory found" else "plenty of memory") + ". Right now: " + (if (lite) "lite" else "full") + ".",
            Modifier.padding(top = 8.dp), maxLines = 3
        )
        Caption(
            "Lite turns off looping animations, the scrolling price strip and off-screen effects, and rebuilds screens a little less often. Text size and your other settings are not touched.",
            Modifier.padding(top = 4.dp), maxLines = 5
        )
    }
}

/**
 * Developer options: pretend to be a smaller phone, so layout on a Redmi or POCO can be checked on any screen. Applied
 * around the whole app (see SimulatedDevice); a chip with Reset shows while it is on. Only ever active in developer mode.
 */
@Composable
internal fun SimulatorCard() {
    val profile by DevLog.deviceProfile.collectAsStateWithLifecycle()
    val choices = listOf(DeviceProfile.OFF) + DeviceProfile.PRESETS
    val chosen = choices.firstOrNull { it.id == profile.id } ?: DeviceProfile.OFF
    SectionCard("Simulate a small phone", subtitle = if (profile.isIdentity) "Off" else "On: " + profile.summary()) {
        Caption("Draws the app in a smaller window, with the density and text size of a budget phone. System bars, the keyboard and dialogs stay real.", maxLines = 4)
        ChoiceCards(
            choices = choices.map { Choice(it.id, if (it.isIdentity) "Off (this phone as it is)" else it.label) },
            selected = if (profile.id == "custom") "" else chosen.id,
            onSelect = { id -> DevLog.setDeviceProfile(choices.first { it.id == id }) },
            modifier = Modifier.padding(top = 8.dp)
        )
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Caption("DENSITY")
            SegmentedTabs(
                labels = DeviceProfile.DENSITY_SCALES.map { "×" + it },
                selected = DeviceProfile.DENSITY_SCALES.indexOf(profile.densityScale).coerceAtLeast(0),
                onSelect = { DevLog.setDeviceProfile(profile.withScales(density = DeviceProfile.DENSITY_SCALES[it])) },
                modifier = Modifier.padding(top = 6.dp)
            )
            Caption("FONT SCALE", Modifier.padding(top = 12.dp))
            SegmentedTabs(
                labels = DeviceProfile.FONT_SCALES.map { "×" + it },
                selected = DeviceProfile.FONT_SCALES.indexOf(profile.fontScale).coerceAtLeast(0),
                onSelect = { DevLog.setDeviceProfile(profile.withScales(font = DeviceProfile.FONT_SCALES[it])) },
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            "Density and font scale multiply the phone's own; the window size keeps the preset's.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
        )
        AppButton("Reset", { DevLog.setDeviceProfile(DeviceProfile.OFF) }, Modifier.fillMaxWidth().padding(top = 12.dp), style = ButtonStyle.OUTLINE, enabled = !profile.isIdentity)
    }
}

private fun DeviceProfile.withScales(density: Float = densityScale, font: Float = fontScale): DeviceProfile =
    DeviceProfile.custom(density, font, base = this)
