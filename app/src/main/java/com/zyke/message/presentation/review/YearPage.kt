package com.zyke.message.presentation.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.review.YearInReview
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.SectionHeader
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.settings.ReportSharing
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** The year the "Your year" page and its entry card look at: this year, or last year until there is enough of this one. */
internal fun yearToReview(messages: List<ClassifiedMessage>, today: LocalDate, zone: ZoneId): Int? =
    listOf(today.year, today.year - 1).firstOrNull {
        YearInReview.monthsWithData(messages, it, zone) >= YearInReview.MIN_MONTHS_FOR_PAGE
    }

@Composable
internal fun YearEntryCard(year: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    FintechCard(modifier, onClick = onOpen) {
        Caption("YOUR $year")
        Text(
            "See your year in review",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Caption("Income, spending, top categories and fun facts. Stays on this phone.", Modifier.padding(top = 4.dp), maxLines = 2)
    }
}

@Composable
internal fun YearPage(messages: List<ClassifiedMessage>, year: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val review = remember(messages, year) { YearInReview.build(messages, year, zone, LocalDate.now(zone)) }

    AppPage("Your year", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FintechCard {
                Caption("YOUR ${review.year}")
                Text(
                    DisplayFormatters.rupees(review.spent),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Caption("spent across ${review.transactionCount} payments and credits", Modifier.padding(top = 4.dp), maxLines = 2)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("Income", DisplayFormatters.rupees(review.income), Modifier.weight(1f))
                    Stat(
                        if (review.net.signum() >= 0) "Saved" else "Over",
                        DisplayFormatters.rupees(review.net.abs()),
                        Modifier.weight(1f)
                    )
                    Stat("Savings rate", review.savingsRatePercent?.let { "$it%" } ?: "Not available", Modifier.weight(1f))
                }
                Caption("Based on ${review.monthsOfData} months of messages", Modifier.padding(top = 12.dp), maxLines = 2)
            }

            SectionHeader("Month by month")
            FintechCard {
                MonthBars(review)
            }

            if (review.topCategories.isNotEmpty()) {
                SectionHeader("Top categories")
                FintechCard {
                    review.topCategories.forEachIndexed { rank, c ->
                        RankRow(c.category.displayString, DisplayFormatters.rupees(c.amount), share(c.amount, review.spent), ChartColors.forRank(rank))
                    }
                }
            }

            if (review.topPayees.isNotEmpty()) {
                SectionHeader("Top places and people")
                FintechCard {
                    review.topPayees.forEachIndexed { rank, p ->
                        RankRow(p.name, DisplayFormatters.rupees(p.amount), share(p.amount, review.spent), ChartColors.forRank(rank))
                    }
                }
            }

            SectionHeader("Fun facts")
            FintechCard {
                facts(review).forEach {
                    Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            AppButton(
                "Share as a picture",
                {
                    DevLog.d("YearPage") { "sharing year card" }
                    val png = YearCardImage.png(review)
                    ReportSharing.sharePng(context, "zyke-your-${review.year}.png", png, "Your ${review.year}")
                },
                Modifier.fillMaxWidth()
            )
            Caption(
                "The picture shows only your total spending, savings and top three categories. It is shared only when you tap Share.",
                maxLines = 3
            )
        }
    }
}

private fun share(part: BigDecimal, total: BigDecimal): Float =
    if (total.signum() <= 0) 0f else part.divide(total, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)

private fun facts(review: YearInReview): List<String> = buildList {
    review.biggestPayment?.let {
        add("Your biggest single payment was ${DisplayFormatters.rupees(it.amount)} to ${it.payee} on ${it.date}.")
    }
    review.busiestMonth?.let { add("${monthName(it)} was your busiest month for spending.") }
    review.quietestMonth?.let { add("${monthName(it)} was your quietest month.") }
    if (review.daysCovered > 0) add("You spent about ${DisplayFormatters.rupees(review.averageDailySpend)} a day.")
    if (review.longestNoSpendStreak > 0) add("Your longest stretch without spending was ${review.longestNoSpendStreak} days.")
    if (review.subscriptionsTotal.signum() > 0) add("Repeating monthly payments came to ${DisplayFormatters.rupees(review.subscriptionsTotal)}.")
}

private fun monthName(month: java.time.Month): String = month.getDisplayName(TextStyle.FULL, Locale.getDefault())

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Caption(label)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RankRow(name: String, amount: String, fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp)) {
            drawRoundRect(track, Offset.Zero, size, CornerRadius(size.height / 2))
            drawRoundRect(color, Offset.Zero, Size(size.width * fraction, size.height), CornerRadius(size.height / 2))
        }
    }
}

@Composable
private fun MonthBars(review: YearInReview) {
    val max = review.months.maxOf { it.spent }
    val bar = MaterialTheme.colorScheme.primary
    val peak = ChartColors.warning
    val busiest = review.busiestMonth
    val months = review.months
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val slot = size.width / months.size
        months.forEachIndexed { i, m ->
            val fraction = if (max.signum() > 0) m.spent.divide(max, 4, RoundingMode.HALF_UP).toFloat() else 0f
            val h = maxOf(size.height * fraction, 3.dp.toPx())
            drawRoundRect(
                if (m.month == busiest) peak else bar,
                Offset(i * slot + slot * 0.15f, size.height - h),
                Size(slot * 0.7f, h),
                CornerRadius(4.dp.toPx())
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        months.forEach {
            Caption(
                it.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
