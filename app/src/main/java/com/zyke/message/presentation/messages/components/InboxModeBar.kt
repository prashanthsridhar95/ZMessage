package com.zyke.message.presentation.messages.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.IconSize
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.tonalFill
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.presentation.messages.MessagesContract.ViewMode

/**
 * One line for the Inbox: whatever describes the current view (the type tabs in the timeline, a count
 * elsewhere) takes the width, and the Timeline / Senders switch sits at the end as two icons, which comes to
 * roughly 20% of a typical phone. The switch keeps its natural width so it is never squeezed on a narrow
 * screen. A single line under both closes the row.
 */
@Composable
fun InboxModeBar(
    mode: ViewMode,
    onMode: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { leading() }
            Box(Modifier.padding(horizontal = Spacing.md), contentAlignment = Alignment.Center) {
                ViewModeToggle(mode, onMode)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    }
}

/** Two icons in one rounded track; the current view is filled. */
@Composable
private fun ViewModeToggle(mode: ViewMode, onMode: (ViewMode) -> Unit) {
    Row(
        Modifier
            .background(tonalFill(Tonal.Raised), AppShapes.chip)
            .padding(2.dp)
    ) {
        ModeIcon(R.drawable.baseline_list_24, "Timeline view", mode == ViewMode.TIMELINE) { onMode(ViewMode.TIMELINE) }
        ModeIcon(R.drawable.baseline_group_24, "Senders view", mode == ViewMode.SENDERS) { onMode(ViewMode.SENDERS) }
    }
}

@Composable
private fun ModeIcon(icon: Int, description: String, selected: Boolean, onClick: () -> Unit) {
    // The pill is 36dp to look right; the tappable area around it is the full 48dp.
    val motion = rememberMotion()
    val haptics = rememberHaptics()
    val pill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, motion.tween(Motion.QUICK_MS), label = "mode-pill")
    val ink by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else mutedColor(), motion.tween(Motion.QUICK_MS), label = "mode-ink")
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .pressFeedback(source, pressedScale = Motion.PRESSED_SCALE)
            .clip(AppShapes.chip)
            .clickable(
                interactionSource = source, indication = LocalIndication.current,
                role = Role.Tab, onClickLabel = description,
                onClick = { if (!selected) { haptics.tick(); onClick() } }
            )
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Radius.chip - 2.dp))
                .background(pill),
            contentAlignment = Alignment.Center
        ) {
            Image(
                modifier = Modifier.size(IconSize.md),
                painter = painterResource(icon),
                colorFilter = ColorFilter.tint(ink),
                contentDescription = description
            )
        }
    }
}
