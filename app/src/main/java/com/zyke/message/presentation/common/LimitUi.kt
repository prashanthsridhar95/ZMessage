package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.LimitLine
import com.zyke.message.domain.dashboard.LimitPeriod
import com.zyke.message.presentation.format.DisplayFormatters

/** Green when there is room, red when the limit is passed; each pair keeps its contrast in light and dark themes. */
data class LimitColors(val good: Color, val bad: Color)

@Composable
fun limitColors(): LimitColors =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        LimitColors(good = Color(0xFF0B7A55), bad = Color(0xFFC62828))
    } else {
        LimitColors(good = Color(0xFF3DD6A0), bad = Color(0xFFFF6B6B))
    }

/** The words for a limit's position: "₹250 over today's limit" or "₹420 available today". Words as well as colour. */
fun limitText(line: LimitLine): String {
    val of = " of ${DisplayFormatters.rupees(line.limit)}"
    return if (line.isOver) {
        "${DisplayFormatters.rupees(line.deficit)} over ${if (line.period == LimitPeriod.DAILY) "today's" else "this month's"} limit$of"
    } else {
        "${DisplayFormatters.rupees(line.available)} ${if (line.period == LimitPeriod.DAILY) "available today" else "left this month"}$of"
    }
}

/** One limit: a bar that fills as it is used, and the line saying what is available or over, in green or red. */
@Composable
fun LimitStatus(line: LimitLine, modifier: Modifier = Modifier) {
    val colors = limitColors()
    val color = if (line.isOver) colors.bad else colors.good
    val fraction = line.used.coerceIn(0f, 1f)
    val text = limitText(line)

    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${line.period.label} limit. $text" }) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).fillMaxHeight().background(color))
        }
    }
}
