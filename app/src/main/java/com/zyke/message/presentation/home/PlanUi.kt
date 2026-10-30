package com.zyke.message.presentation.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor

/**
 * Shared pieces of the Goals, Reminders and Calendar pages, so the three read as one family: the same header,
 * the same state labels and the same empty page.
 */

internal val Good = Color(0xFF2E9E5B)
internal val Warn = Color(0xFFD9951B)
internal val Danger = Color(0xFFD64550)

/** How a state is shown: a colour and, always, a word, so nothing depends on colour alone. */
internal enum class Tone { GOOD, WARN, BAD, NEUTRAL }

@Composable
internal fun toneColor(tone: Tone): Color = when (tone) {
    Tone.GOOD -> Good
    Tone.WARN -> Warn
    Tone.BAD -> Danger
    Tone.NEUTRAL -> MaterialTheme.colorScheme.secondary
}

@Composable
internal fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Text(
        text,
        modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1
    )
}

@Composable
internal fun SectionHeader(text: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            Modifier.heading(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
            color = mutedColor()
        )
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Caption(count.toString())
        }
    }
}

/** The headline of a page: one number that answers "how am I doing?", with a line of context and an optional bar. */
@Composable
internal fun PageSummary(label: String, value: String, caption: String, modifier: Modifier = Modifier, extra: (@Composable () -> Unit)? = null) {
    FintechCard(modifier) {
        Caption(label.uppercase())
        Text(
            value,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Caption(caption, Modifier.padding(top = 4.dp), maxLines = 2)
        extra?.invoke()
    }
}

/** Nothing here yet: says what the page is for and offers the one thing to do about it. */
@Composable
internal fun EmptyPlan(icon: Int, title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    FintechCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
            }
            Text(
                title, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center
            )
            Caption(body, Modifier.padding(top = 4.dp, bottom = 16.dp), maxLines = 4, textAlign = TextAlign.Center)
            AppButton(actionLabel, onAction)
        }
    }
}

/** A ring that fills as a goal is met, with the percentage inside it. */
@Composable
internal fun ProgressRing(fraction: Float, tone: Tone, modifier: Modifier = Modifier.size(56.dp)) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.primary else toneColor(tone)
    val percent = (fraction * 100).toInt()
    Box(modifier.clearAndSetSemantics { contentDescription = "$percent percent saved" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arc = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
            if (fraction > 0f) drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = stroke)
        }
        Text("$percent%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}
