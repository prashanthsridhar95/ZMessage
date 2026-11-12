package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.CrossfadeContent
import com.zyke.message.presentation.theme.Spacing

/**
 * Chooses whether the lists below show money spent or money received. Two equal segments, the chosen one filled by an
 * indicator that slides across with a tick, announced as one selectable group; the chart above always shows both.
 */
@Composable
internal fun DirectionSwitch(direction: FlowDirection, onSelect: (FlowDirection) -> Unit, modifier: Modifier = Modifier) {
    SlidingSegments(
        choices = FlowDirection.entries.map { Choice(it, it.label) },
        selected = direction,
        onSelect = onSelect,
        modifier = modifier,
        description = "Show spent or received"
    )
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
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = Spacing.sm)
    ) {
        DirectionSwitch(direction, onSelect, Modifier.fillMaxWidth())
        CrossfadeContent(caption, label = "scope-caption") { text ->
            Caption(text, Modifier.padding(start = Spacing.xs, top = Spacing.xs), maxLines = 2)
        }
    }
}
