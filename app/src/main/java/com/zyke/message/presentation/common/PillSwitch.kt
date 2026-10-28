package com.zyke.message.presentation.common

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Chooses one of a few options; the chosen one is filled, as in the Money tab's Spent / Received switch. */
@Composable
fun PillSwitch(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Text(
                label,
                Modifier
                    .clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary,
                maxLines = 1
            )
        }
    }
}

/** The same switch with a symbol for each side, for where there is room for little; each still has a spoken name. */
@Composable
fun IconPillSwitch(glyphs: List<String>, descriptions: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        glyphs.forEachIndexed { index, glyph ->
            val on = index == selected
            Text(
                glyph,
                Modifier
                    .clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { contentDescription = descriptions[index] }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary,
                maxLines = 1
            )
        }
    }
}
