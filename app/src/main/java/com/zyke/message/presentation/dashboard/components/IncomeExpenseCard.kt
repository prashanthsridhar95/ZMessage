package com.zyke.message.presentation.dashboard.components

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
    direction: FlowDirection,
    onDirection: (FlowDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    val dates = (expense.map { it.start } + income.map { it.start }).distinct().sorted()
    val spentByDate = expense.associate { it.start to it.amount }
    val receivedByDate = income.associate { it.start to it.amount }
    val spent = dates.map { spentByDate[it] ?: BigDecimal.ZERO }
    val received = dates.map { receivedByDate[it] ?: BigDecimal.ZERO }
    val peak = (spent + received).maxOrNull()?.takeIf { it.signum() > 0 }?.toFloat() ?: 1f
    val monthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
    val caption = when (period) {
        DashboardPeriod.THIS_MONTH, DashboardPeriod.LAST_MONTH -> monthFmt.format(dashboard.range.start)
        DashboardPeriod.LAST_3_MONTHS -> "Last 3 months"
        DashboardPeriod.ALL_TIME -> "All time"
    }
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(modifier.fillMaxWidth()) {
        Caption(caption.uppercase())
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Total("Spent", dashboard.spentTotal, ExpenseColor, direction == FlowDirection.SPENT) { onDirection(FlowDirection.SPENT) }
            Total("Received", dashboard.receivedTotal, IncomeColor, direction == FlowDirection.RECEIVED) { onDirection(FlowDirection.RECEIVED) }
        }

        if (dates.size >= 2) {
            Canvas(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .height(140.dp)
                    .semantics {
                        contentDescription = "Line chart of money spent and received. Spent ${DisplayFormatters.rupees(dashboard.spentTotal)}, " +
                            "received ${DisplayFormatters.rupees(dashboard.receivedTotal)}."
                    }
            ) {
                val pad = 6.dp.toPx()
                val usable = size.height - 2 * pad
                val step = (size.width - 2 * pad) / (dates.size - 1)
                drawLine(gridColor, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), strokeWidth = 1.dp.toPx())

                fun draw(values: List<BigDecimal>, color: Color) {
                    val points = values.mapIndexed { i, v -> Offset(pad + step * i, size.height - pad - usable * (v.toFloat() / peak)) }
                    val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawCircle(color, radius = 4.dp.toPx(), center = points.last())
                }
                draw(received, IncomeColor)
                draw(spent, ExpenseColor)
            }
            val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Caption(dayFmt.format(dates.first()))
                Caption(dayFmt.format(dates.last()))
            }
        } else {
            Caption("The lines appear once there are a few days of activity.", Modifier.padding(top = 16.dp), maxLines = 2)
        }
    }
}

@Composable
private fun Total(label: String, amount: BigDecimal, color: Color, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(role = Role.Tab, onClickLabel = "Show $label below", onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Caption(label)
        }
        Text(
            text = rememberAnimatedRupees(amount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            maxLines = 1
        )
    }
}
