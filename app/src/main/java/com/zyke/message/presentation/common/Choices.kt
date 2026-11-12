package com.zyke.message.presentation.common

import com.zyke.message.presentation.theme.tonalFill
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.motion.Motion
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.messages.components.Caption

/** One option of a choice: what it is called and, when the name is not enough, what it means. */
data class Choice<T>(val value: T, val title: String, val description: String? = null)

/**
 * One of a few things, each as its own card that says what it does. The chosen card is ringed, tinted and ticked, so the
 * answer to "what is it set to?" is visible without reading, and the whole card is the tap target.
 */
@Composable
fun <T> ChoiceCards(choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val motion = rememberMotion()
    val tick = rememberTick()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        choices.forEach { choice ->
            val chosen = choice.value == selected
            val shape = AppShapes.control
            val idle = tonalFill(Tonal.Quiet)
            val on = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            val fill by animateColorAsState(if (chosen) on else idle, motion.tween(Motion.QUICK_MS), label = "choice")
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(shape)
                    .drawBehind { drawRect(fill) }
                    .border(if (chosen) 2.dp else 0.dp, if (chosen) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { if (!chosen) tick(); onSelect(choice.value) })
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        choice.title, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium, color = MaterialTheme.colorScheme.primary
                    )
                    if (choice.description != null) Caption(choice.description, maxLines = 3)
                }
                if (chosen) Text("✓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Two to four short options side by side, like Light, Dark and Automatic; the chosen one is filled. */
@Composable
fun <T> SegmentedChoice(choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val shape = AppShapes.control
    val tick = rememberTick()
    Row(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        choices.forEach { choice ->
            val chosen = choice.value == selected
            Text(
                choice.title,
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(AppShapes.chip)
                    .background(if (chosen) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { if (!chosen) tick(); onSelect(choice.value) })
                    .padding(horizontal = Spacing.sm, vertical = Spacing.md),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
