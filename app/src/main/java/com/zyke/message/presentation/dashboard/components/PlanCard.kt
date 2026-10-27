package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.LimitLine
import com.zyke.message.domain.dashboard.LimitPeriod
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanInsights
import com.zyke.message.domain.plan.SalaryStatus
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LimitStatus
import com.zyke.message.presentation.common.limitColors
import com.zyke.message.presentation.common.rememberGrowingFraction
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The plan the person described in Settings, laid against the month so far: where the salary is going, what is
 * left to spend (green) or overspent (red), which planned payments have happened, and how saving is going.
 */
@Composable
internal fun PlanCard(plan: MoneyPlan, insights: PlanInsights?, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    if (insights == null) {
        SectionCard("Your month's plan", modifier) {
            Caption(
                "Add your salary, regular outflows like parents or charity, investments and what you want to save, " +
                    "and this shows what is yours to spend each day.",
                maxLines = 4
            )
            AppButton("Set up in Settings", onEdit, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, modifier = Modifier.padding(top = 12.dp))
        }
        return
    }

    val colors = limitColors()
    SectionCard("Your month's plan", modifier) {
        if (insights.income.signum() > 0) {
            SalarySplit(insights)
            Spacer(Modifier.height(16.dp))

            // The headline: what is left to spend, in green, or how far over, in red.
            val over = insights.isOverPlan
            val headline = if (over) {
                "${DisplayFormatters.rupees(insights.spendableLeft.negate())} over what the plan leaves to spend"
            } else {
                "${DisplayFormatters.rupees(insights.spendableLeft)} left to spend"
            }
            Text(
                headline,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (over) colors.bad else colors.good
            )
            insights.perDayLeft?.let {
                Caption("About ${DisplayFormatters.rupees(it)} a day for the next ${insights.daysLeft} days", maxLines = 2)
            }
            if (insights.spendable.signum() > 0) {
                LimitStatus(LimitLine(LimitPeriod.MONTHLY, insights.spendable, insights.spentOnTheRest), Modifier.padding(top = 10.dp))
            } else {
                Text(
                    "The plan uses more than the whole salary, so nothing is left to spend.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.bad
                )
            }
        }

        insights.salaryStatus?.let { SalaryLine(plan, insights, it) }

        if (insights.items.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            insights.items.forEachIndexed { index, status ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RowTitle(status.item.name)
                        val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                        Caption(
                            when {
                                status.isPaid -> "Paid ${status.paidOn?.let(dayFmt::format).orEmpty()}"
                                status.item.dayOfMonth != null -> "Expected around day ${status.item.dayOfMonth}"
                                else -> "Not seen this month yet"
                            },
                            color = if (status.isPaid) colors.good else com.zyke.message.presentation.messages.components.mutedColor()
                        )
                    }
                    Text(
                        DisplayFormatters.rupees(status.item.amount),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (insights.savingsGoal.signum() > 0) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            val saved = insights.savedSoFar
            val behind = saved.signum() < 0
            RowTitle("Saving")
            Text(
                if (behind) {
                    "Spending is ${DisplayFormatters.rupees(saved.negate())} ahead of what came in"
                } else {
                    "${DisplayFormatters.rupees(saved)} kept so far of ${DisplayFormatters.rupees(insights.savingsGoal)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (behind) colors.bad else colors.good
            )
            Bar(
                fraction = if (behind) 0f else (saved.toFloat() / insights.savingsGoal.toFloat()).coerceIn(0f, 1f),
                color = if (behind) colors.bad else colors.good
            )
        }

        AppButton("Edit plan in Settings", onEdit, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, modifier = Modifier.padding(top = 14.dp))
    }
}

private val PlanColors = listOf(Color(0xFF4C8BF5), Color(0xFF9B6BDF), Color(0xFFF5A623))

/** The salary as one bar split into what is committed, invested, put aside and left to spend. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SalarySplit(insights: PlanInsights) {
    val parts = listOf(
        Triple("Regular outflows", insights.outflows, PlanColors[0]),
        Triple("Investments", insights.investments, PlanColors[1]),
        Triple("Saving", insights.savingsGoal, PlanColors[2]),
        Triple("To spend", insights.spendable.max(BigDecimal.ZERO), MaterialTheme.colorScheme.primary)
    ).filter { it.second.signum() > 0 }
    val total = parts.sumOf { it.second }.max(BigDecimal.ONE)
    val summary = parts.joinToString(", ") { "${it.first} ${DisplayFormatters.rupees(it.second)}" }

    Row(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .semantics { contentDescription = "Where the salary of ${DisplayFormatters.rupees(insights.income)} goes. $summary" }
    ) {
        parts.forEach { (_, amount, color) ->
            Box(Modifier.weight((amount.toFloat() / total.toFloat()).coerceAtLeast(0.01f)).fillMaxHeight().background(color))
        }
    }
    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEach { (label, amount, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Caption("$label ${DisplayFormatters.rupees(amount)}", maxLines = 2)
            }
        }
    }
}

@Composable
private fun SalaryLine(plan: MoneyPlan, insights: PlanInsights, status: SalaryStatus) {
    val colors = limitColors()
    val salary = DisplayFormatters.rupees(insights.income)
    val (text, color) = when (status) {
        SalaryStatus.RECEIVED -> "Salary of ${DisplayFormatters.rupees(insights.salaryReceived ?: insights.income)} received" to colors.good
        SalaryStatus.LATE -> "Salary of $salary hasn't arrived yet" to colors.bad
        SalaryStatus.EXPECTED -> "Salary of $salary expected${plan.salaryDay?.let { " around day $it" }.orEmpty()}" to com.zyke.message.presentation.messages.components.mutedColor()
    }
    Text(text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    val shown = rememberGrowingFraction(fraction)
    Box(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Box(Modifier.fillMaxWidth(shown.coerceAtLeast(0.02f)).fillMaxHeight().background(color))
    }
}
