package com.zyke.message.presentation.messages.components

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
            Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
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
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(2.dp)
    ) {
        ModeIcon(R.drawable.baseline_list_24, "Timeline view", mode == ViewMode.TIMELINE) { onMode(ViewMode.TIMELINE) }
        ModeIcon(R.drawable.baseline_group_24, "Senders view", mode == ViewMode.SENDERS) { onMode(ViewMode.SENDERS) }
    }
}

@Composable
private fun ModeIcon(icon: Int, description: String, selected: Boolean, onClick: () -> Unit) {
    // The pill is 36dp to look right; the tappable area around it is the full 48dp.
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClickLabel = description, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Image(
                modifier = Modifier.size(20.dp),
                painter = painterResource(icon),
                colorFilter = ColorFilter.tint(if (selected) MaterialTheme.colorScheme.onPrimary else mutedColor()),
                contentDescription = description
            )
        }
    }
}
