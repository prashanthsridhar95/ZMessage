package com.zyke.message.presentation.dashboard.components

import com.zyke.message.presentation.common.rememberReduceMotion
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.AppButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.dashboard.TrendPoint
import com.zyke.message.presentation.common.rememberAnimatedRupees
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IncomeColor = Color(0xFF34A853)
private val ExpenseColor = Color(0xFFE8505B)

/**
 * The top of the Spend tab: money out and money in over the period as two lines. The two totals underneath are
 * also the switch for the lists below (spent or received).
 */
@Composable
internal fun IncomeExpenseCard(
    dashboard: Dashboard,
    expense: List<TrendPoint>,
    income: List<TrendPoint>,
    period: DashboardPeriod,
    /** Moves to the pay cycle before (-1) or after (1); null where the chart cannot be moved. */
    onShift: ((Int) -> Unit)? = null,
    canGoForward: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dates = (expense.map { it.start } + income.map { it.start }).distinct().sorted()
    val spentByDate = expense.associate { it.start to it.amount }
    val receivedByDate = income.associate { it.start to it.amount }
    val spent = dates.map { spentByDate[it] ?: BigDecimal.ZERO }
    val received = dates.map { receivedByDate[it] ?: BigDecimal.ZERO }
    val monthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
    val caption = when (period) {
        DashboardPeriod.THIS_MONTH, DashboardPeriod.LAST_MONTH, DashboardPeriod.CUSTOM -> periodName(dashboard.range)
        DashboardPeriod.LAST_3_MONTHS -> "Last 3 months"
        DashboardPeriod.ALL_TIME -> "All time"
    }
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onShift != null) AppButton("‹", { onShift(-1) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            Caption(caption.uppercase(), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (onShift != null) AppButton("›", { onShift(1) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = canGoForward)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Total("Spent", dashboard.spentTotal, ExpenseColor)
            Total("Received", dashboard.receivedTotal, IncomeColor)
        }

        if (dates.size >= 2) {
            val last = dates.size - 1
            val maxZoom = (dates.size / MIN_VISIBLE_POINTS.toFloat()).coerceAtLeast(1f)
            var zoom by rememberSaveable(dates.first(), dates.size) { mutableFloatStateOf(1f) }
            var view by rememberSaveable(dates.first(), dates.size) { mutableFloatStateOf(0f) }
            val span = last / zoom
            val viewAt = view.coerceIn(0f, (last - span).coerceAtLeast(0f))

            // The lines draw themselves in from the left each time the period or its data changes.
            val reveal = remember { Animatable(0f) }
            val reduceMotion = rememberReduceMotion()
            LaunchedEffect(dates.first(), dates.size, spent.lastOrNull(), received.lastOrNull()) {
                if (reduceMotion) {
                    reveal.snapTo(1f)
                } else {
                    reveal.snapTo(0f)
                    reveal.animateTo(1f, tween(REVEAL_MS))
                }
            }
            val shownSpan by animateFloatAsState(span, tween(ZOOM_MS), label = "zoom-span")
            val shownView by animateFloatAsState(viewAt, tween(ZOOM_MS), label = "zoom-view")

            Canvas(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .height(150.dp)
                    .pointerInput(dates.size) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, maxZoom)
                            // Pan in points: how many points the finger moved across the visible stretch.
                            val visibleSpan = last / newZoom
                            val moved = -pan.x / size.width * visibleSpan
                            zoom = newZoom
                            view = (view.coerceIn(0f, (last - span).coerceAtLeast(0f)) + moved).coerceIn(0f, (last - visibleSpan).coerceAtLeast(0f))
                        }
                    }
                    .semantics {
                        contentDescription = "Line chart of money spent and received. Spent ${DisplayFormatters.rupees(dashboard.spentTotal)}, " +
                            "received ${DisplayFormatters.rupees(dashboard.receivedTotal)}. Pinch to zoom."
                    }
            ) {
                val pad = 6.dp.toPx()
                val usableWidth = size.width - 2 * pad
                val usable = size.height - 2 * pad
                fun x(index: Int) = pad + (index - shownView) / shownSpan.coerceAtLeast(MIN_SPAN) * usableWidth

                // The scale follows what is on screen, so zooming into a quiet stretch shows its detail.
                val from = kotlin.math.floor(shownView).toInt().coerceIn(0, last)
                val to = kotlin.math.ceil(shownView + shownSpan).toInt().coerceIn(0, last)
                val peak = (spent.subList(from, to + 1) + received.subList(from, to + 1)).maxOrNull()?.takeIf { it.signum() > 0 }?.toFloat() ?: 1f

                drawLine(gridColor, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), strokeWidth = 1.dp.toPx())
                repeat(GRID_LINES) { i ->
                    val y = pad + usable * (i + 1) / (GRID_LINES + 1)
                    drawLine(gridColor.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }

                fun draw(values: List<BigDecimal>, color: Color) {
                    val points = (from..to).map { i -> Offset(x(i), size.height - pad - usable * (values[i].toFloat() / peak)) }
                    val whole = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                    val measure = PathMeasure().apply { setPath(whole, false) }
                    val drawn = Path()
                    measure.getSegment(0f, measure.length * reveal.value, drawn, true)
                    drawPath(drawn, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    val tip = if (reveal.value >= 1f) points.last() else measure.getPosition(measure.length * reveal.value)
                    drawCircle(color, radius = 4.dp.toPx(), center = tip)
                    // Zoomed in far enough, each day or month gets its own dot.
                    if (shownSpan <= DOT_SPAN) points.forEach { drawCircle(color, radius = 2.5.dp.toPx(), center = it) }
                }
                draw(received, IncomeColor)
                draw(spent, ExpenseColor)
            }
            val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Caption(dayFmt.format(dates[kotlin.math.round(viewAt).toInt().coerceIn(0, last)]), Modifier.weight(1f))
                AppButton("−", { zoom = (zoom / ZOOM_STEP).coerceIn(1f, maxZoom) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = zoom > 1f)
                AppButton("+", { zoom = (zoom * ZOOM_STEP).coerceIn(1f, maxZoom) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = zoom < maxZoom)
                Caption(
                    dayFmt.format(dates[kotlin.math.round(viewAt + span).toInt().coerceIn(0, last)]),
                    Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        } else {
            Caption("Not enough days yet", Modifier.padding(top = 16.dp))
        }
    }
}

private const val MIN_VISIBLE_POINTS = 4
private const val MIN_SPAN = 0.5f
private const val REVEAL_MS = 900
private const val ZOOM_MS = 220
private const val ZOOM_STEP = 1.6f
private const val DOT_SPAN = 14f
private const val GRID_LINES = 3

/** One of the two lines of the chart, named, coloured as it is drawn, with what it adds up to. */
@Composable
private fun Total(label: String, amount: BigDecimal, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Caption(label)
        }
        Text(
            text = rememberAnimatedRupees(amount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}
