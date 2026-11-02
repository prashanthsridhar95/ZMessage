package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.device.DeviceProfile
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The developer's "Simulate a small phone": draws [content] in a window of the pretend phone's size, centred, with a thin
 * border, at the pretend density and font scale, so a Pixel can show what a Redmi would. [profile] is the identity
 * (nothing changes, content is composed directly) unless developer mode is on and a pretend phone is chosen.
 *
 * What it cannot do: the status and navigation bars, the cut-out and the keyboard are the real phone's, and dialogs and
 * system windows (permission prompts, the share sheet) are the real size. Everything the app draws itself is simulated.
 */
@Composable
fun SimulatedDevice(profile: DeviceProfile, onReset: () -> Unit, content: @Composable () -> Unit) {
    if (profile.isIdentity) {
        content()
        return
    }
    val real = LocalDensity.current
    val simulated = remember(real, profile) { Density(real.density * profile.densityScale, real.fontScale * profile.fontScale) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val availableWidth = constraints.maxWidth
        val availableHeight = constraints.maxHeight
        val width = if (profile.widthDp > 0) min(availableWidth, (profile.widthDp * simulated.density).roundToInt()) else availableWidth
        val height = if (profile.heightDp > 0) min(availableHeight, (profile.heightDp * simulated.density).roundToInt()) else availableHeight
        val window = IntSize((width / simulated.density).roundToInt(), (height / simulated.density).roundToInt())
        Box(
            Modifier
                .size(with(real) { width.toDp() }, with(real) { height.toDp() })
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                .clipToBounds()
        ) {
            CompositionLocalProvider(LocalDensity provides simulated, LocalSimulatedWindow provides window) {
                content()
            }
        }
        SimulatingChip(profile, window, onReset, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 2.dp))
    }
}

/** A small, always-visible reminder that the screen is pretending, with the way out. Drawn at the real density. */
@Composable
private fun SimulatingChip(profile: DeviceProfile, window: IntSize, onReset: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(50))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Simulating ${profile.summary()} (${window.width}×${window.height})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1
        )
        AppButton("Reset", onReset, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
}
