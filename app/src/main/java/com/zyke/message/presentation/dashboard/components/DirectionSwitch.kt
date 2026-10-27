package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.FlowDirection

/**
 * Chooses whether the lists below show money spent or money received. It is a switch in its own right, with the
 * chosen side filled, so it is plain which one is on; the chart above always shows both.
 */
@Composable
internal fun DirectionSwitch(direction: FlowDirection, onSelect: (FlowDirection) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlowDirection.entries.forEach { option ->
            val selected = option == direction
            Text(
                option.label,
                Modifier
                    .clip(shape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary,
                maxLines = 1
            )
        }
    }
}
