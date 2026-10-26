package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.messages.components.Caption

data class TileOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val supporting: String? = null
)

/**
 * Choices as a grid of tiles. Selecting one tints it and adds a tick, so state is readable at
 * a glance and the touch target is the whole tile.
 */
@Composable
fun TileGrid(options: List<TileOption>, modifier: Modifier = Modifier, columns: Int = 2) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option -> Tile(option, Modifier.weight(1f)) }
                // Keep the last row's tiles the same width as the others.
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(option: TileOption, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (option.selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .border(
                width = if (option.selected) 1.5.dp else 1.dp,
                color = if (option.selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                shape = shape
            )
            .selectable(selected = option.selected, onClick = option.onClick, role = Role.RadioButton)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Normal,
                color = if (option.selected) accent else MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            option.supporting?.let { Caption(it) }
        }
        if (option.selected) {
            Text("✓", modifier = Modifier.padding(start = 8.dp), color = accent, fontWeight = FontWeight.Bold)
        }
    }
}
