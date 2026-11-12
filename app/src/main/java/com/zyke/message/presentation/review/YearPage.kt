package com.zyke.message.presentation.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
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
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.dashboard.components.Chevron
import com.zyke.message.presentation.motion.CrossfadeKeyed
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.settings.ReportSharing
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.State
import com.zyke.message.domain.review.YearMonth
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.HIDDEN_AMOUNT
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.rememberGrowingState
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer

/** The year the "Your year" page and its entry card look at: this year, or last year until there is enough of this one. */
internal fun yearToReview(messages: List<ClassifiedMessage>, today: LocalDate, zone: ZoneId): Int? =
    listOf(today.year, today.year - 1).firstOrNull {
        YearInReview.monthsWithData(messages, it, zone) >= YearInReview.MIN_MONTHS_FOR_PAGE
    }

@Composable
internal fun YearEntryCard(year: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    FintechCard(modifier, onClick = onOpen) {
        CardHeader("Your $year", trailing = { Chevron() })
        Text(
            "See your year in review",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Caption("Income, spending, top categories and fun facts. Stays on this phone.", Modifier.padding(top = 4.dp), maxLines = 2)
    }
}

private val GUTTER = 12.dp

@Composable
internal fun YearPage(messages: List<ClassifiedMessage>, year: Int, onBack: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var chosen by rememberSaveable { mutableStateOf(year) }
    // Both are worked out on a background thread; the previous year stays on screen until the next one is ready.
    val years by produceState(listOf(year), messages) {
        value = withContext(Dispatchers.Default) { YearInReview.yearsWithData(messages, zone) }.ifEmpty { listOf(year) }
    }
    val review by produceState<YearInReview?>(null, messages, chosen) {
        value = withContext(Dispatchers.Default) { YearInReview.build(messages, chosen, zone, today) }
    }

    AppPage("Your year", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GUTTER)) {
            if (years.size > 1) {
                Chips(
                    years.map { ChipOption(it.toString(), it == chosen) },
                    onSelect = { chosen = years[it] }
                )
            }
            val r = review
            // Another year fades in over the one before instead of replacing it at once.
            CrossfadeKeyed(r, contentKey = { it?.year }, label = "year") { shown ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GUTTER)) {
                    if (shown == null) {
                        Caption("Working out your year…", Modifier.padding(4.dp))
                    } else {
                        YearStory(shown, today)
                    }
                }
            }
        }
    }
}

@Composable
private fun YearStory(review: YearInReview, today: LocalDate) {
    val context = LocalContext.current
    val hidden = LocalPrivacyMode.current.hidden
    val money: (BigDecimal) -> String = { if (hidden) HIDDEN_AMOUNT else DisplayFormatters.rupees(it) }

    Hero(review, onShare = {
        DevLog.d("YearPage") { "sharing year card" }
        val png = YearCardImage.png(review, today)
        ReportSharing.sharePng(context, "zyke-your-${review.year}.png", png, "Your ${review.year}")
    })

    if (review.isEmpty) {
        StoryCard("Nothing to show", "No ${review.year} yet", "There are no payments or credits from ${review.year} on this phone.") {
            EmptyNote("Pick another year above, or come back once messages arrive.")
        }
        return
    }

    val months = remember(review, today) { review.monthsToShow(today).filter { it.spent.signum() > 0 } }
    StoryCard(
        "Month by month",
        review.busiestMonth?.let { "${monthName(it)} was your biggest month" } ?: "How your spending moved",
        null,
        keyNumber = months.takeIf { it.isNotEmpty() }?.let { m ->
            money(m.sumOf { it.spent }.divide(BigDecimal(m.size), 0, RoundingMode.HALF_UP)) + " a month"
        },
        keyIsMoney = true
    ) { MonthBars(review, today) }

    StoryCard(
        "Top categories",
        review.topCategories.firstOrNull()?.let { "${it.category.displayString} took the biggest share" } ?: "Where it went",
        null,
        keyNumber = review.topCategories.firstOrNull()?.let { "${percent(it.amount, review.spent)}% of spending" }
    ) {
        if (review.topCategories.isEmpty()) EmptyNote("No spending in ${review.year} to sort into categories.")
        review.topCategories.forEachIndexed { rank, c ->
            RankRow(c.category.displayString, DisplayFormatters.rupees(c.amount), share(c.amount, review.spent), ChartColors.forRank(rank), percent(c.amount, review.spent))
        }
    }

    StoryCard(
        "Top places and people",
        review.topPayees.firstOrNull()?.let { "Most went to ${it.name}" } ?: "Who got your money",
        null
    ) {
        if (review.topPayees.isEmpty()) EmptyNote("No payments to show yet.")
        review.topPayees.forEachIndexed { rank, p ->
            PayeeRow(rank + 1, p.name, DisplayFormatters.rupees(p.amount), p.count, ChartColors.forRank(rank))
        }
    }

    StatGrid(review, money)

    StoryCard(
        "Longest quiet stretch",
        when {
            review.daysCovered == 0 -> "No days covered yet"
            review.longestNoSpendStreak == 0 -> "You spent something every day"
            else -> "Your longest run without spending"
        },
        null,
        keyNumber = review.longestNoSpendStreak.takeIf { it > 0 }?.let { DisplayFormatters.count(it, "day", "days") }
    ) {
        if (review.daysCovered == 0) EmptyNote("Not enough days to find a streak.")
        else Caption(
            if (review.longestNoSpendStreak == 0) "Across ${review.daysCovered} days there was no spending-free day."
            else "Out of ${review.daysCovered} days covered, that is ${percent(BigDecimal(review.longestNoSpendStreak), BigDecimal(review.daysCovered))}% of the year in one go.",
            maxLines = 3
        )
    }

    StoryCard(
        "Repeating payments",
        if (review.subscriptionsTotal.signum() > 0) "Subscriptions and bills that repeat" else "No repeating payments found",
        null,
        keyNumber = review.subscriptionsTotal.takeIf { it.signum() > 0 }?.let { money(it) },
        keyIsMoney = true
    ) {
        if (review.subscriptionsTotal.signum() > 0) {
            Caption("${percent(review.subscriptionsTotal, review.spent)}% of everything you spent.", maxLines = 2)
        } else {
            EmptyNote("Payments that come back every month will appear here.")
        }
    }

    IncomeVsSpend(review, money)

    StoryCard("Fun facts", "A few more things", null) {
        val facts = remember(review) { facts(review) }
        if (facts.isEmpty()) EmptyNote("Facts show up once there is some spending.")
        facts.forEach { Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
    }

    Caption(
        "The shared picture shows only your total spending, savings and top three categories. Nothing leaves this phone until you tap Share.",
        Modifier.padding(horizontal = 4.dp),
        maxLines = 3
    )
}

@Composable
private fun Hero(review: YearInReview, onShare: () -> Unit) {
    val rate = review.savingsRatePercent
    FintechCard {
        Caption("YOUR ${review.year}")
        Text(
            if (review.isEmpty) "A quiet year" else "You spent",
            Modifier.padding(top = 4.dp).heading(),
            style = MaterialTheme.typography.titleMedium
        )
        PrivateAmount(
            DisplayFormatters.rupees(review.spent),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            fit = true
        )
        Caption("across ${DisplayFormatters.count(review.transactionCount, "payment or credit", "payments and credits")}", maxLines = 2)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroStat("Received", DisplayFormatters.rupees(review.income), Modifier.weight(1f), money = true)
            HeroStat(
                "Savings rate", rate?.let { "$it%" } ?: "n/a", Modifier.weight(1f),
                color = when {
                    rate == null -> MaterialTheme.colorScheme.primary
                    rate >= 20 -> AppColors.success
                    rate >= 0 -> AppColors.warning
                    else -> AppColors.danger
                }
            )
        }
        Caption(
            when {
                rate == null -> "No income recorded, so no savings rate."
                rate >= 0 -> "You kept $rate% of what came in."
                else -> "You spent ${-rate}% more than came in."
            },
            Modifier.padding(top = 12.dp), maxLines = 2
        )
        Caption("Based on ${DisplayFormatters.count(review.monthsOfData, "month", "months")} of messages", maxLines = 2)
        AppButton("Share as a picture", onShare, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = !review.isEmpty)
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier, money: Boolean = false, color: Color = MaterialTheme.colorScheme.primary) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(12.dp)
    ) {
        Caption(label)
        val style = MaterialTheme.typography.titleLarge
        if (money) PrivateAmount(value, style = style, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        else Text(value, style = style, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** One idea per card: a caption, a headline sentence, optionally the key number big, then the visual. */
@Composable
private fun StoryCard(
    caption: String,
    headline: String,
    sub: String?,
    keyNumber: String? = null,
    keyIsMoney: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    FintechCard {
        Text(
            caption.uppercase(),
            Modifier.heading(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
            color = mutedColor()
        )
        Text(headline, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (keyNumber != null) {
            val style = MaterialTheme.typography.headlineMedium
            if (keyIsMoney) PrivateAmount(keyNumber, Modifier.padding(top = 4.dp), style = style, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            else Text(keyNumber, Modifier.padding(top = 4.dp), style = style, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        if (sub != null) Caption(sub, Modifier.padding(top = 4.dp), maxLines = 3)
        Spacer(Modifier.size(12.dp))
        content()
    }
}

@Composable
private fun EmptyNote(text: String) {
    Caption(text, Modifier.fillMaxWidth().padding(vertical = 8.dp), maxLines = 3)
}

@Composable
private fun StatGrid(review: YearInReview, money: (BigDecimal) -> String) {
    val day = review.biggestDay
    val payment = review.biggestPayment
    val weekday = review.busiestWeekday
    val tiles = listOf(
        Triple("Biggest day", day?.let { money(it.amount) }, day?.let { dateText(it.date) } ?: "No spending yet"),
        Triple("Biggest payment", payment?.let { money(it.amount) }, payment?.let { "${it.payee} · ${dateText(it.date)}" } ?: "No payments yet"),
        Triple("Busiest weekday", weekday?.let { dayName(it.day) }, weekday?.let { "${money(it.amount)} in total" } ?: "No spending yet"),
        Triple("Average a day", review.takeIf { it.daysCovered > 0 }?.let { money(it.averageDailySpend) }, if (review.daysCovered > 0) "over ${review.daysCovered} days" else "No days covered yet")
    )
    Column(verticalArrangement = Arrangement.spacedBy(GUTTER)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(GUTTER)) {
                pair.forEach { (label, value, note) ->
                    FintechCard(Modifier.weight(1f).fillMaxHeight()) {
                        Caption(label, maxLines = 2)
                        Text(
                            value ?: "–",
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Caption(note, Modifier.padding(top = 4.dp), maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomeVsSpend(review: YearInReview, money: (BigDecimal) -> String) {
    val hasIncome = review.income.signum() > 0
    StoryCard(
        "Income versus spending",
        when {
            !hasIncome -> "No income recorded"
            review.net.signum() >= 0 -> "You came out ahead"
            else -> "You spent more than came in"
        },
        null,
        keyNumber = if (hasIncome) money(review.net.abs()) + if (review.net.signum() >= 0) " saved" else " over" else null,
        keyIsMoney = true
    ) {
        if (!hasIncome) {
            EmptyNote("Credits such as salary will appear here once they are in your messages.")
        } else {
            val top = review.income.max(review.spent)
            FlowBar("Received", review.income, share(review.income, top), AppColors.success, money)
            FlowBar("Spent", review.spent, share(review.spent, top), ChartColors.forRank(0), money)
        }
    }
}

@Composable
private fun FlowBar(label: String, amount: BigDecimal, fraction: Float, color: Color, money: (BigDecimal) -> String) {
    val grown by rememberGrowingState(fraction)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "$label ${money(amount)}" }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            PrivateAmount(DisplayFormatters.rupees(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).drawBehind {
            drawRoundRect(track, Offset.Zero, size, CornerRadius(size.height / 2))
            drawRoundRect(color, Offset.Zero, Size(size.width * grown.coerceIn(0f, 1f), size.height), CornerRadius(size.height / 2))
        })
    }
}

@Composable
private fun PayeeRow(rank: Int, name: String, amount: String, count: Int, color: Color) {
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Number $rank, $name, ${DisplayFormatters.count(count, "payment", "payments")}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).background(color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Text(initial, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Caption(DisplayFormatters.count(count, "payment", "payments"))
        }
        PrivateAmount(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun percent(part: BigDecimal, total: BigDecimal): Int = (share(part, total) * 100).let { Math.round(it) }

private fun dateText(date: LocalDate): String =
    "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"

private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.getDefault())

private fun share(part: BigDecimal, total: BigDecimal): Float =
    if (total.signum() <= 0) 0f else part.divide(total, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)

private fun facts(review: YearInReview): List<String> = buildList {
    review.quietestMonth?.let { add("${monthName(it)} was your quietest month.") }
    if (review.daysCovered > 0) add("You made ${DisplayFormatters.count(review.transactionCount, "payment or credit", "payments and credits")} over ${review.daysCovered} days.")
    review.biggestPayment?.let { add("Your biggest single payment went to ${it.payee} on ${dateText(it.date)}.") }
}

private fun monthName(month: java.time.Month): String = month.getDisplayName(TextStyle.FULL, Locale.getDefault())

@Composable
private fun RankRow(name: String, amount: String, fraction: Float, color: Color, percent: Int) {
    val grown by rememberGrowingState(fraction)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "$name, $percent percent of spending" }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$percent%", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = mutedColor(), maxLines = 1)
            PrivateAmount(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).drawBehind {
            drawRoundRect(track, Offset.Zero, size, CornerRadius(size.height / 2))
            drawRoundRect(color, Offset.Zero, Size(size.width * grown, size.height), CornerRadius(size.height / 2))
        })
    }
}

/** Smallest width a month gets, so a 3-letter name always fits and the chart scrolls sideways instead of squeezing. */
private val MONTH_SLOT = 48.dp
private val CHART_HEIGHT = 120.dp

/** "Mar", the month's short name. */
private fun shortMonth(month: java.time.Month): String = month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

@Composable
private fun MonthBars(review: YearInReview, today: LocalDate?) {
    val months = remember(review, today) { review.monthsToShow(today) }
    if (months.isEmpty()) {
        Caption("No months to show yet", maxLines = 1)
        return
    }
    val max = months.maxOf { it.spent }
    val bar = MaterialTheme.colorScheme.primary
    val peak = ChartColors.warning
    val busiest = review.busiestMonth
    val privacy = com.zyke.message.presentation.common.LocalPrivacyMode.current
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val scroll = rememberScrollState()
    // Opens at the latest month, which is the one most worth seeing.
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    val chosen = selected?.let(months::getOrNull)
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (chosen == null) {
            Caption("Tap a month to see what you spent", maxLines = 1)
        } else {
            val inProgress = review.isMonthInProgress(chosen.month, today)
            Caption(shortMonth(chosen.month) + (if (inProgress) " so far" else "") + " \u00B7 ", maxLines = 1)
            com.zyke.message.presentation.common.PrivateAmount(
                DisplayFormatters.rupees(chosen.spent),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Caption(" spent", maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(top = 8.dp)) {
        months.forEachIndexed { i, m ->
            val fraction = if (max.signum() > 0) m.spent.divide(max, 4, RoundingMode.HALF_UP).toFloat() else 0f
            val inProgress = review.isMonthInProgress(m.month, today)
            val isSelected = selected == i
            val color = when {
                m.month == busiest -> peak
                else -> bar
            }
            val amountWords = if (privacy.hidden) "amount hidden" else "${DisplayFormatters.rupees(m.spent)} spent"
            Column(
                Modifier
                    .width(MONTH_SLOT)
                    .selectable(
                        selected = isSelected, role = Role.Button,
                        onClick = { selected = if (isSelected) null else i }
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${shortMonth(m.month)}${if (inProgress) " so far" else ""}: $amountWords"
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth().height(CHART_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .width(28.dp)
                            .height(maxOf(CHART_HEIGHT * fraction, 3.dp))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            // The month still going on is lighter: it is only the part of it so far.
                            .background(color.copy(alpha = if (inProgress) 0.45f else if (selected != null && !isSelected) 0.6f else 1f))
                    )
                }
                Text(
                    shortMonth(m.month),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected || inProgress) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1
                )
                Box(Modifier.heightIn(min = 12.dp))
            }
        }
    }
    if (months.any { review.isMonthInProgress(it.month, today) }) {
        Caption("The lighter bar is this month so far", Modifier.padding(top = 4.dp), maxLines = 1)
    }
}
