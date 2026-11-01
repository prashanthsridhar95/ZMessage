@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.forecast.CutReason
import com.zyke.message.domain.forecast.CutSuggestion
import com.zyke.message.domain.forecast.ForecastStories
import com.zyke.message.domain.forecast.ForecastStory
import com.zyke.message.domain.forecast.SpendForecast
import com.zyke.message.domain.forecast.Verdict
import com.zyke.message.domain.plan.PlanInsights
import com.zyke.message.domain.plan.SalaryRunway
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.limitColors
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.Danger
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.home.Warn
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

private const val SHOWN_BILLS = 4

/**
 * Where the cycle is heading, as a story. On the page it is one tile: the verdict in a line and the salary strip. The
 * sheet it opens tells the rest in order: the verdict, one chart (spent, projected, plan limit), three numbers, the
 * strip, what is driving it and what is still to be paid before the cycle ends.
 */
@Composable
internal fun ForecastCard(
    forecast: SpendForecast,
    modifier: Modifier = Modifier,
    runway: SalaryRunway? = null,
    summary: MonthlySummary? = null,
    insights: PlanInsights? = null
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val story = remember(forecast, runway, insights, summary) { ForecastStories.build(forecast, runway, insights, summary) }

    FintechCard(modifier, onClick = { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption("WHERE THIS IS HEADING")
                Headline(story, forecast, runway, Modifier.padding(top = 4.dp))
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
        }
        // How long the plan's money lasts is a second way of reading the same pace, so it sits in this card.
        if (runway != null) SalaryStrip(runway, Modifier.padding(top = 14.dp))
    }

    if (open) {
        AdaptiveSheet("Where this is heading", { open = false }, asPage = true) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Headline(story, forecast, runway, large = true)
                ForecastChart(summary, story)
                StatRow(story)
                if (runway != null) {
                    Column {
                        Caption("UNTIL PAYDAY")
                        SalaryStrip(runway, Modifier.padding(top = 8.dp))
                    }
                }
                Drivers(forecast, summary)
                Upcoming(forecast)
                Workings(forecast)
            }
        }
    }
}

private fun verdictColorTone(verdict: Verdict): Tone = when (verdict) {
    Verdict.ON_TRACK -> Tone.GOOD
    Verdict.PACE -> Tone.NEUTRAL
    else -> Tone.BAD
}

/** The answer in a sentence: a coloured verdict, then the detail with its figures. */
@Composable
private fun Headline(story: ForecastStory, forecast: SpendForecast, runway: SalaryRunway?, modifier: Modifier = Modifier, large: Boolean = false) {
    val colors = limitColors()
    val dayFormat = FormatCache.pattern("d MMM", Locale.getDefault())
    // Local copies: properties of another module cannot be smart-cast.
    val end = forecast.endBalance
    val needed = forecast.cardNeeded
    val projected = story.projectedSpend
    val gap = story.planGap
    val runsOut = story.runsOutOn
    val target = if (runway?.paydayIsSalaryDay == true) "salary" else "the end of the cycle"
    val days = story.daysBeforePayday

    val title: String
    val detail: List<Part>
    when (story.verdict) {
        Verdict.ON_TRACK -> {
            title = "On track"
            detail = when {
                projected != null && gap != null -> listOf(words("to spend"), money(DisplayFormatters.rupees(projected)), words("—"), money(DisplayFormatters.rupees(gap.abs())), words(if (gap.signum() >= 0) "under your plan" else "over your plan"))
                projected != null -> listOf(words("to spend"), money(DisplayFormatters.rupees(projected)), words("by the end of the cycle"))
                end != null -> listOf(words("with about"), money(DisplayFormatters.rupees(end)), words("left at the end"))
                else -> listOf(words("if spending carries on as it has"))
            }
        }
        Verdict.RUNS_OUT -> {
            title = "You'll run out on " + (runsOut?.let { dayWords(it) } ?: "a day soon")
            detail = listOf(words("${days} ${if (days == 1) "day" else "days"} before $target"))
        }
        Verdict.USED_UP -> {
            title = "Your plan's money is used up"
            detail = listOf(words("${days} ${if (days == 1) "day" else "days"} before $target"))
        }
        Verdict.SHORT -> {
            title = "Your balance falls short"
            detail = listOf(words("by about"), money(DisplayFormatters.rupees(needed ?: BigDecimal.ZERO))) +
                (forecast.runsOutOn?.let { listOf(words("from around ${dayFormat.format(it)}")) } ?: emptyList()) +
                words("— you would need a credit card for the rest")
        }
        Verdict.PACE -> {
            title = "At this pace"
            detail = listOf(words("you spend about"), money(DisplayFormatters.rupees(forecast.bankPerDay + forecast.cardPerDay)), words("a day"))
        }
    }
    val color = when (verdictColorTone(story.verdict)) {
        Tone.GOOD -> colors.good
        Tone.BAD -> colors.bad
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            Modifier.heading(),
            style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        MoneyLine(detail, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, moneyColor = MaterialTheme.colorScheme.onSurface, moneyWeight = FontWeight.Bold)
    }
}

// ---- Chart ----

/**
 * One chart for the cycle so far and the rest: what has been spent (solid), the pace carried on to the end (dotted),
 * and the level at which the plan's money is used up (a dashed line), with a marker where the pace crosses it.
 */
@Composable
private fun ForecastChart(summary: MonthlySummary?, story: ForecastStory) {
    summary ?: return
    val cur = summary.cumulativeSpent
    if (cur.size < 2) return
    val projected = story.projectedSpend
    val limit = story.planLimit
    val days = maxOf(summary.cycleDays, cur.size).coerceAtLeast(2)
    val scheme = MaterialTheme.colorScheme
    val spentColor = scheme.primary
    val projectedColor = scheme.primary.copy(alpha = 0.6f)
    val limitColor = Warn
    val crossColor = Danger
    val gridColor = scheme.onSurface.copy(alpha = 0.10f)
    val peak = remember(summary, story) {
        listOfNotNull(cur.last(), projected, limit).maxOrNull()?.toFloat()?.takeIf { it > 0f } ?: 1f
    }
    val hidden = LocalPrivacyMode.current.hidden
    val spoken = if (hidden) "Chart of spending so far, the pace to the end of the cycle and the plan limit. Amounts hidden." else buildString {
        append("Chart. Spent ${DisplayFormatters.rupees(cur.last())} after ${cur.size} days. ")
        if (projected != null) append("At this pace ${DisplayFormatters.rupees(projected)} by the end of the cycle. ")
        if (limit != null) append("The plan's money is used up at ${DisplayFormatters.rupees(limit)}.")
    }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = spoken }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    fun x(i: Float) = w * i / (days - 1).toFloat()
                    fun y(v: Float) = h - (h - 10f) * (v / peak)
                    val last = cur.last().toFloat()
                    val lastX = x((cur.size - 1).toFloat())
                    val spentPath = Path().apply { cur.forEachIndexed { i, v -> if (i == 0) moveTo(x(0f), y(v.toFloat())) else lineTo(x(i.toFloat()), y(v.toFloat())) } }
                    val projPath = if (projected != null && cur.size < days) Path().apply {
                        moveTo(lastX, y(last)); lineTo(x((days - 1).toFloat()), y(projected.toFloat()))
                    } else null
                    // Where the pace crosses the plan limit: a fraction of the way along the projected line.
                    val cross = if (projected != null && limit != null && cur.size < days && projected.toFloat() > limit.toFloat() && last <= limit.toFloat() && projected.toFloat() > last) {
                        val t = (limit.toFloat() - last) / (projected.toFloat() - last)
                        Offset(lastX + t * (x((days - 1).toFloat()) - lastX), y(limit.toFloat()))
                    } else null
                    val limitStroke = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                    val dotted = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f)), cap = StrokeCap.Round)
                    val solid = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                    onDrawBehind {
                        drawLine(gridColor, Offset(0f, h), Offset(w, h), 1.dp.toPx())
                        drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), 1.dp.toPx())
                        if (limit != null) drawLine(limitColor, Offset(0f, y(limit.toFloat())), Offset(w, y(limit.toFloat())), limitStroke.width, pathEffect = limitStroke.pathEffect)
                        projPath?.let { drawPath(it, projectedColor, style = dotted) }
                        drawPath(spentPath, spentColor, style = solid)
                        drawCircle(spentColor, 5.dp.toPx(), Offset(lastX, y(last)))
                        if (projPath != null) drawCircle(projectedColor, 5.dp.toPx(), Offset(x((days - 1).toFloat()), y(projected!!.toFloat())), style = Stroke(2.dp.toPx()))
                        cross?.let { drawCircle(crossColor, 6.dp.toPx(), it) }
                    }
                }
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("Day 1")
            Caption("Day $days")
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Swatch("Spent", spentColor, SwatchKind.SOLID)
            if (projected != null) Swatch("At this pace", projectedColor, SwatchKind.DOTTED)
            if (limit != null) Swatch("Plan limit", limitColor, SwatchKind.DASHED)
            if (story.verdict == Verdict.RUNS_OUT || story.verdict == Verdict.USED_UP) Swatch("Runs out", crossColor, SwatchKind.DOT)
        }
    }
}

private enum class SwatchKind { SOLID, DOTTED, DASHED, DOT }

@Composable
private fun Swatch(label: String, color: Color, kind: SwatchKind) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(18.dp).height(8.dp)) {
            val cy = size.height / 2f
            when (kind) {
                SwatchKind.SOLID -> drawLine(color, Offset(0f, cy), Offset(size.width, cy), 3.dp.toPx(), StrokeCap.Round)
                SwatchKind.DOTTED -> drawLine(color, Offset(0f, cy), Offset(size.width, cy), 2.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(2f, 8f)))
                SwatchKind.DASHED -> drawLine(color, Offset(0f, cy), Offset(size.width, cy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)))
                SwatchKind.DOT -> drawCircle(color, 4.dp.toPx(), Offset(size.width / 2f, cy))
            }
        }
        Spacer(Modifier.width(6.dp))
        Caption(label)
    }
}

// ---- Three numbers ----

@Composable
private fun StatRow(story: ForecastStory) {
    val spent = story.spentSoFar
    val projected = story.projectedSpend
    val left = story.planLeft
    val stats = buildList {
        if (spent != null) add(Triple("Spent so far", DisplayFormatters.rupees(spent), null as Tone?))
        if (projected != null) add(Triple("Projected month-end", DisplayFormatters.rupees(projected), null))
        if (left != null) {
            if (left.signum() >= 0) add(Triple("Plan left", DisplayFormatters.rupees(left), Tone.GOOD))
            else add(Triple("Over plan by", DisplayFormatters.rupees(left.abs()), Tone.BAD))
        }
    }
    if (stats.isEmpty()) return
    val hidden = LocalPrivacyMode.current.hidden
    val spoken = if (hidden) "Spent so far, projected month end and plan left, amounts hidden" else stats.joinToString(". ") { "${it.first} ${it.second}" }
    val large = LocalDensity.current.fontScale > 1.15f
    val box = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp)).padding(12.dp)
    if (large) {
        Column(box.semantics(mergeDescendants = true) { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.forEach { (label, value, tone) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Caption(label, Modifier.weight(1f), maxLines = 2)
                    StatValue(value, tone)
                }
            }
        }
    } else {
        Row(box.semantics(mergeDescendants = true) { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.forEach { (label, value, tone) ->
                Column(Modifier.weight(1f)) {
                    Caption(label, maxLines = 2)
                    StatValue(value, tone, Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun StatValue(value: String, tone: Tone?, modifier: Modifier = Modifier) {
    val colors = limitColors()
    PrivateAmount(
        value, modifier,
        color = when (tone) { Tone.GOOD -> colors.good; Tone.BAD -> colors.bad; else -> MaterialTheme.colorScheme.primary },
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1
    )
}

// ---- What's driving it ----

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.heading(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Drivers(forecast: SpendForecast, summary: MonthlySummary?) {
    val cuts = forecast.suggestions.take(3)
    val movers = remember(summary) { summary?.changes?.filter { it.delta.signum() > 0 }?.take(3).orEmpty() }
    if (cuts.isEmpty() && movers.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("What's driving it")
        if (cuts.isNotEmpty()) {
            cuts.forEachIndexed { index, cut ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                CutRow(cut)
            }
            Caption(
                "Doing all of these keeps about ${DisplayFormatters.rupees(forecast.totalSaving)} by the end of the cycle".takeIf { !LocalPrivacyMode.current.hidden } ?: "Doing all of these keeps some money by the end of the cycle",
                Modifier.padding(top = 6.dp), maxLines = 3
            )
        } else {
            movers.forEachIndexed { index, change ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RowTitle(change.category.displayString)
                        MoneyLine(listOf(words("up"), money(DisplayFormatters.rupees(change.delta)), words("on last month")), style = MaterialTheme.typography.bodySmall, color = mutedColor())
                    }
                    change.percent?.let { StatusPill("+$it%", Tone.BAD) }
                }
            }
        }
    }
}

@Composable
private fun CutRow(cut: CutSuggestion) {
    val usual = cut.usual
    val detail = when (cut.reason) {
        CutReason.ABOVE_USUAL -> listOf(words("On pace for"), money(DisplayFormatters.rupees(cut.projected))) +
            (if (usual != null) listOf(words("usually"), money(DisplayFormatters.rupees(usual))) else emptyList())
        CutReason.BIGGEST_SHARE -> listOf(words("On pace for"), money(DisplayFormatters.rupees(cut.projected)), words("one of your biggest"))
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            RowTitle(cut.category.displayString)
            MoneyLine(detail, style = MaterialTheme.typography.bodySmall, color = mutedColor())
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Caption("Could save")
            PrivateAmount(DisplayFormatters.rupees(cut.saving), color = limitColors().good, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// ---- Upcoming before month end ----

@Composable
private fun Upcoming(forecast: SpendForecast) {
    val bills = forecast.upcoming
    val salaryOn = forecast.salaryOn
    if (bills.isEmpty() && (salaryOn == null || forecast.expectedIncome.signum() <= 0)) return
    val dayFormat = FormatCache.pattern("d MMM", Locale.getDefault())
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) bills else bills.take(SHOWN_BILLS)
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Upcoming before month end")
        if (salaryOn != null && forecast.expectedIncome.signum() > 0) {
            UpcomingRow("Salary expected", dayFormat.format(salaryOn), "+ " + DisplayFormatters.rupees(forecast.expectedIncome), income = true)
        }
        shown.forEachIndexed { index, bill ->
            if (index > 0 || (salaryOn != null && forecast.expectedIncome.signum() > 0)) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            UpcomingRow(bill.title, dayFormat.format(bill.date), DisplayFormatters.rupees(bill.amount), income = false)
        }
        if (bills.size > SHOWN_BILLS) {
            val label = if (all) "Show fewer" else "Show all ${bills.size}"
            Text(
                label,
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = label) { all = !all }.padding(vertical = 12.dp),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
            )
        }
        if (bills.isNotEmpty()) {
            MoneyLine(
                listOf(words("${bills.size} ${if (bills.size == 1) "payment" else "payments"} ·"), money(DisplayFormatters.rupees(forecast.billsDue)), words("in all")),
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = mutedColor()
            )
        }
    }
}

@Composable
private fun UpcomingRow(title: String, date: String, amount: String, income: Boolean) {
    val hidden = LocalPrivacyMode.current.hidden
    val spoken = if (hidden) "$title on $date, amount hidden" else "$title on $date, $amount"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            Caption(date)
        }
        Spacer(Modifier.width(12.dp))
        PrivateAmount(amount, color = if (income) limitColors().good else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ---- How it is worked out ----

@Composable
private fun Workings(forecast: SpendForecast) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = if (open) "Hide how this is worked out" else "How this is worked out"
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = label) { open = !open }.padding(vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
        )
        if (!open) return
        forecast.balanceNow?.let { Line("In your accounts now", DisplayFormatters.rupees(it)) }
        if (forecast.expectedIncome.signum() > 0) Line("Salary expected", "+ ${DisplayFormatters.rupees(forecast.expectedIncome)}")
        Line("Everyday spending, ${forecast.daysLeft} more days", "− ${DisplayFormatters.rupees(forecast.projectedBankSpend)}")
        if (forecast.billsDue.signum() > 0) Line("Bills and auto-debits due", "− ${DisplayFormatters.rupees(forecast.billsDue)}")
        if (forecast.projectedCardSpend.signum() > 0) {
            MoneyLine(
                listOf(words("On your cards, about"), money(DisplayFormatters.rupees(forecast.projectedCardSpend)), words("more will be added to the next bill")),
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = mutedColor()
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        PrivateAmount(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}
