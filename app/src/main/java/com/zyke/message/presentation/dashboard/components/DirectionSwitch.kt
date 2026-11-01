package com.zyke.message.presentation.dashboard.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.presentation.messages.components.Caption

/**
 * Chooses whether the lists below show money spent or money received. Two equal segments with the chosen one filled,
 * each at least 48dp tall, announced as one selectable group; the chart above always shows both.
 */
@Composable
internal fun DirectionSwitch(direction: FlowDirection, onSelect: (FlowDirection) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(3.dp)
            .selectableGroup()
            .semantics { contentDescription = "Show spent or received" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlowDirection.entries.forEach { option ->
            val selected = option == direction
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary,
                    maxLines = 1
                )
            }
        }
    }
}

/** "Showing: spending · March", the one line that says what the sections under the switch are about right now. */
internal fun scopeCaption(direction: FlowDirection, period: String, extra: List<String> = emptyList()): String =
    "Showing: ${if (direction == FlowDirection.SPENT) "spending" else "income"} · " + (listOf(period) + extra).joinToString(" · ")

/**
 * The header the Spent / Received sections hang under. It stays at the top while they scroll, so the choice and what
 * it currently covers are always in view.
 */
@Composable
internal fun DirectionHeader(
    direction: FlowDirection,
    caption: String,
    onSelect: (FlowDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 8.dp, bottom = 8.dp)
    ) {
        DirectionSwitch(direction, onSelect, Modifier.fillMaxWidth())
        AnimatedContent(
            targetState = caption,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "scope-caption"
        ) { text ->
            Caption(text, Modifier.padding(start = 4.dp, top = 6.dp), maxLines = 2)
        }
    }
}
