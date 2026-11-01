package com.zyke.message.presentation.dashboard.components

import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.PrivateAmount
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import com.zyke.message.presentation.theme.AppColors
import androidx.compose.foundation.Image
import kotlin.math.roundToInt
import kotlin.math.abs
import com.zyke.message.presentation.common.rememberGrowingState
import com.zyke.message.presentation.common.heading
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.zyke.message.R
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.messages.components.mutedColor
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.Grade
import com.zyke.message.domain.dashboard.Remark
import com.zyke.message.domain.dashboard.ReportCard
import com.zyke.message.domain.dashboard.Subject
import com.zyke.message.domain.dashboard.Trend
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/** Grades read as a colour as well as a letter, so the month's mood is felt at a glance. */
private object GradeColors {
    val good: Color @Composable @ReadOnlyComposable get() = AppColors.success
    val fair: Color @Composable @ReadOnlyComposable get() = AppColors.warning
    val poor: Color @Composable @ReadOnlyComposable get() = AppColors.danger
    val none: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    @ReadOnlyComposable
    fun of(grade: Grade?): Color = when (grade) {
        Grade.A, Grade.B -> good
        Grade.C -> fair
        Grade.D, Grade.F -> poor
        null -> none
    }
}

/**
 * The report card in one tappable tile: a small ring around the overall grade and what stands out.
 * The letter carries the meaning; the colour only echoes it.
 */
@Composable
internal fun ReportCardSummary(card: ReportCard, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val accent = GradeColors.of(card.overall)
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.16f), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.28f)), shape)
            .clickable(onClickLabel = "Open report card", onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GradeRing(card.overall, size = 60.dp, stroke = 5.dp, letterStyle = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "REPORT CARD · ${DisplayFormatters.monthName(card.month).uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = accent
            )
            Text(
                remarkText(card.remark),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2
            )
        }
        Image(
            modifier = Modifier.size(20.dp),
            painter = painterResource(R.drawable.baseline_chevron_right_24),
            colorFilter = ColorFilter.tint(mutedColor()),
            contentDescription = null
        )
    }
}

/** This month's marks in full: a hero with the overall grade, the biggest lever, then a metric row per category. */
@Composable
internal fun ReportCardSheet(card: ReportCard, onDismiss: () -> Unit) {
    val levers = remember(card) { biggestLevers(card) }
    AdaptiveSheet("Report card", onDismiss, asPage = true) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Hero(card)

            if (levers.isNotEmpty()) {
                SectionLabel("BIGGEST LEVER")
                levers.forEach { LeverCard(it) }
            } else if (card.subjects.isNotEmpty()) {
                FintechCard {
                    Text("No weak spots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = GradeColors.good)
                    Caption("Every category is holding up. Keep going.", Modifier.padding(top = 4.dp), maxLines = 2)
                }
            }

            SectionLabel("BY CATEGORY")
            if (card.subjects.isNotEmpty()) {
                FintechCard {
                    card.subjects.forEachIndexed { i, subject ->
                        if (i > 0) Spacer(Modifier.height(4.dp))
                        MetricRow(subject)
                    }
                }
            } else {
                FintechCard {
                    Text("No marks yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Caption(
                        "Categories are graded once you have a budget or a previous month to compare against.",
                        Modifier.padding(top = 4.dp),
                        maxLines = 3
                    )
                }
            }

            if (card.unreadMessages > 0) {
                Caption(
                    "${DisplayFormatters.count(card.unreadMessages, "money message", "money messages")} couldn't be read, " +
                        "so these marks may be kinder than the truth.",
                    Modifier.padding(horizontal = 6.dp),
                    maxLines = 3
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 6.dp, top = 6.dp).heading(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        color = mutedColor()
    )
}

/** A category that needs attention and the one thing to do about it. */
private class Lever(val subject: Subject, val tip: String)

/** Up to two of the weakest marks (C or worse), worst first, each with a plain next step. */
private fun biggestLevers(card: ReportCard): List<Lever> =
    card.subjects
        .filter { (it.grade?.points ?: 4) <= Grade.C.points }
        .sortedWith(compareBy<Subject> { it.grade?.points ?: 4 }.thenByDescending { it.spent })
        .take(2)
        .map { s ->
            val budget = s.budget
            val name = s.category.displayString
            val tip = when {
                budget != null && s.spent > budget ->
                    "Cut ${name.lowercase()} by ${DisplayFormatters.rupees(s.spent - budget)} to get back within its budget."
                s.trend == Trend.UP -> "${name} is climbing. Look at its biggest payments this month and set a budget to hold it."
                else -> "Look at your biggest ${name.lowercase()} payments this month and see what can wait."
            }
            Lever(s, tip)
        }

@Composable
private fun LeverCard(lever: Lever) {
    val s = lever.subject
    val accent = GradeColors.of(s.grade)
    FintechCard(Modifier.semantics(mergeDescendants = true) {
        contentDescription = "What to do about ${s.category.displayString}, grade ${s.grade?.label ?: "none"}. ${lever.tip}"
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradeChip(s.grade)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                RowTitle(s.category.displayString)
                Text("What to do", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent)
            }
        }
        Text(lever.tip, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The overall grade, large, inside a ring that fills with the score, over a soft glow of the grade's colour. */
@Composable
private fun Hero(card: ReportCard) {
    val accent = GradeColors.of(card.overall)
    val shape = RoundedCornerShape(32.dp)
    val surface = MaterialTheme.colorScheme.surface
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.20f), surface.copy(alpha = 0f))))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.25f)), shape)
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Report card for ${DisplayFormatters.monthName(card.month)}. Overall grade " +
                    "${card.overall?.label ?: "not available yet"}. ${remarkText(card.remark)}"
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "REPORT CARD · ${DisplayFormatters.monthName(card.month).uppercase()}",
            Modifier.heading(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = accent
        )
        Spacer(Modifier.height(20.dp))
        GradeRing(card.overall, size = 168.dp, stroke = 10.dp, letterStyle = MaterialTheme.typography.displayLarge.copy(fontSize = (84f / LocalDensity.current.fontScale.coerceAtLeast(1f)).sp))
        Spacer(Modifier.height(20.dp))
        Text(
            overallLine(card),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            remarkText(card.remark),
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = mutedColor(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("SPENT", DisplayFormatters.rupees(card.spent), Modifier.weight(1f))
            StatTile("RECEIVED", DisplayFormatters.rupees(card.received), Modifier.weight(1f))
        }
        Caption(
            "This month so far, against the same days last month",
            Modifier.padding(top = 14.dp),
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = mutedColor()
        )
        PrivateAmount(
            value,
            Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}

private fun overallLine(card: ReportCard): String {
    val saved = card.savingsPercent
    return when {
        saved == null -> "Overall"
        saved >= 0 -> "Kept $saved% of what came in"
        else -> "Spent ${-saved}% more than came in"
    }
}

private fun remarkText(remark: Remark): String = when (remark) {
    is Remark.OverBudget -> "${remark.category.displayString} is ${DisplayFormatters.rupees(remark.over)} over its budget."
    is Remark.Rising -> "${remark.category.displayString} is up ${remark.percent}% on last month."
    is Remark.Improved -> "${remark.category.displayString} is down ${remark.percent}% on last month. Well done."
    is Remark.Saving -> "Steady: you are keeping ${remark.percent}% of your income."
    Remark.OnTrack -> "Nothing stands out yet. Keep going."
}

/** One category as a metric row: label and amount, grade chip, a thin progress bar, a line of explanation and a trend chip. */
@Composable
private fun MetricRow(subject: Subject) {
    val accent = GradeColors.of(subject.grade)
    val budget = subject.budget
    val over = budget != null && subject.spent > budget
    val basis = when {
        budget != null && budget.signum() > 0 -> budget
        subject.previousSpent.signum() > 0 -> subject.previousSpent.max(subject.spent)
        else -> null
    }
    val fraction = basis?.let { (subject.spent.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) } ?: 0f
    val grown by rememberGrowingState(fraction)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val barColor = if (over) GradeColors.poor else accent
    val privacy = com.zyke.message.presentation.common.LocalPrivacyMode.current
    val money: (java.math.BigDecimal) -> String = { if (privacy.hidden) com.zyke.message.presentation.common.HIDDEN_AMOUNT else DisplayFormatters.rupees(it) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${subject.category.displayString}, grade ${subject.grade?.label ?: "none yet"}. " +
                    "Spent ${money(subject.spent)}. ${trendText(subject, money)}"
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(subject.category.displayString)
                PrivateAmount(
                    DisplayFormatters.rupees(subject.spent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(12.dp))
            DeltaChip(subject)
            Spacer(Modifier.width(8.dp))
            GradeChip(subject.grade)
        }
        if (basis != null) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .drawBehind {
                        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                        drawRoundRect(track, Offset.Zero, size, r)
                        val filled = size.width * grown.coerceAtLeast(0.02f)
                        drawRoundRect(barColor, Offset.Zero, Size(filled, size.height), r)
                    }
            )
        }
        Caption(
            buildString {
                if (budget != null) append("${money(subject.spent)} of ${money(budget)} budget \u00B7 ")
                append(trendText(subject, money))
            },
            Modifier.padding(top = 8.dp),
            color = if (over) GradeColors.poor else mutedColor(),
            maxLines = 2
        )
    }
}

/** A small arrow-and-percent chip for the change on last month; rising spend reads as a caution. */
@Composable
private fun DeltaChip(subject: Subject) {
    val (glyph, tint) = when (subject.trend) {
        Trend.UP -> "\u25B2" to GradeColors.poor
        Trend.DOWN -> "\u25BC" to GradeColors.good
        Trend.SAME -> "=" to GradeColors.none
        Trend.NEW -> "NEW" to GradeColors.none
    }
    val percent = if (subject.previousSpent.signum() > 0 && (subject.trend == Trend.UP || subject.trend == Trend.DOWN)) {
        abs(((subject.spent - subject.previousSpent).toDouble() / subject.previousSpent.toDouble()) * 100).roundToInt()
    } else {
        null
    }
    Text(
        listOfNotNull(glyph, percent?.let { "$it%" }).joinToString(" "),
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = tint,
        maxLines = 1
    )
}

private fun trendText(subject: Subject, money: (java.math.BigDecimal) -> String): String = when (subject.trend) {
    Trend.UP -> {
        val percent = if (subject.previousSpent.signum() > 0) {
            (((subject.spent - subject.previousSpent).toDouble() / subject.previousSpent.toDouble()) * 100).roundToInt()
        } else {
            null
        }
        "▲ ${percent?.let { "$it% " }.orEmpty()}from ${money(subject.previousSpent)}"
    }
    Trend.DOWN -> {
        val percent = if (subject.previousSpent.signum() > 0) {
            abs(((subject.spent - subject.previousSpent).toDouble() / subject.previousSpent.toDouble()) * 100).roundToInt()
        } else {
            null
        }
        "▼ ${percent?.let { "$it% " }.orEmpty()}from ${money(subject.previousSpent)}"
    }
    Trend.SAME -> "same as last month"
    Trend.NEW -> "new this month"
}

/** A letter in a softly tinted circle; a dash when there is nothing to grade yet. */
@Composable
private fun GradeChip(grade: Grade?) {
    val accent = GradeColors.of(grade)
    Box(
        Modifier.size(44.dp).background(accent.copy(alpha = 0.16f), CircleShape).border(BorderStroke(1.dp, accent.copy(alpha = 0.4f)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(grade?.label ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
    }
}

/**
 * The grade as a big letter inside a ring that fills with the score, with a soft glow behind it.
 * The ring is decoration; the letter is what is read out.
 */
@Composable
private fun GradeRing(grade: Grade?, size: Dp, stroke: Dp, letterStyle: androidx.compose.ui.text.TextStyle) {
    val accent = GradeColors.of(grade)
    val score = ((grade?.points ?: 0) / 4f).coerceAtLeast(if (grade == null) 0f else 0.08f)
    val sweep by rememberGrowingState(score)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    Box(
        Modifier
            .size(size)
            .drawBehind {
                val width = stroke.toPx()
                val inset = width / 2 + 2.dp.toPx()
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                // Glow, then the track, then the score.
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent)), radius = this.size.minDimension / 2)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
                if (sweep > 0f) {
                    // Sweep gradients start at 3 o'clock; turn the whole arc so it starts at 12.
                    rotate(-90f) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.45f), accent), center = center),
                            startAngle = 0f,
                            sweepAngle = 360f * sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width, cap = StrokeCap.Round)
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(grade?.label ?: "–", style = letterStyle, fontWeight = FontWeight.Bold, color = accent)
    }
}
