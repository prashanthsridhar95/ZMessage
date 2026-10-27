package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.Image
import kotlin.math.roundToInt
import kotlin.math.abs
import com.zyke.message.presentation.common.rememberGrowingFraction
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
    val good = Color(0xFF3DD6A0)
    val fair = Color(0xFFF5B942)
    val poor = Color(0xFFFF6B6B)
    val none = Color(0xFF8A9BA8)

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

/** This month's marks in full: a hero with the overall grade, then a slim card per category. */
@Composable
internal fun ReportCardSheet(card: ReportCard, onDismiss: () -> Unit) {
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Hero(card)

            if (card.subjects.isNotEmpty()) {
                Text(
                    "BY CATEGORY",
                    Modifier.padding(start = 6.dp, top = 6.dp).heading(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = mutedColor()
                )
                card.subjects.forEach { SubjectCard(it) }
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
        GradeRing(card.overall, size = 168.dp, stroke = 10.dp, letterStyle = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp))
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
        Text(
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

/** One category: its mark, how much of its budget or of last month it has used, and which way it is moving. */
@Composable
private fun SubjectCard(subject: Subject) {
    val accent = GradeColors.of(subject.grade)
    val budget = subject.budget
    val over = budget != null && subject.spent > budget
    val basis = when {
        budget != null && budget.signum() > 0 -> budget
        subject.previousSpent.signum() > 0 -> subject.previousSpent.max(subject.spent)
        else -> null
    }
    val fraction = basis?.let { (subject.spent.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) } ?: 0f
    val grown = rememberGrowingFraction(fraction)
    val shape = RoundedCornerShape(22.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)), shape)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${subject.category.displayString}, grade ${subject.grade?.label ?: "none yet"}. " +
                    "Spent ${DisplayFormatters.rupees(subject.spent)}. ${trendText(subject)}"
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(subject.category.displayString)
                Text(
                    DisplayFormatters.rupees(subject.spent),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(12.dp))
            GradeChip(subject.grade)
        }

        if (basis != null) {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(grown.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), if (over) GradeColors.poor else accent)))
                )
            }
        }
        Caption(
            buildString {
                if (budget != null) append("${DisplayFormatters.rupees(subject.spent)} of ${DisplayFormatters.rupees(budget)} budget · ")
                append(trendText(subject))
            },
            Modifier.padding(top = 8.dp),
            color = if (over) GradeColors.poor else mutedColor(),
            maxLines = 2
        )
    }
}

private fun trendText(subject: Subject): String = when (subject.trend) {
    Trend.UP -> {
        val percent = if (subject.previousSpent.signum() > 0) {
            (((subject.spent - subject.previousSpent).toDouble() / subject.previousSpent.toDouble()) * 100).roundToInt()
        } else {
            null
        }
        "▲ ${percent?.let { "$it% " }.orEmpty()}from ${DisplayFormatters.rupees(subject.previousSpent)}"
    }
    Trend.DOWN -> {
        val percent = if (subject.previousSpent.signum() > 0) {
            abs(((subject.spent - subject.previousSpent).toDouble() / subject.previousSpent.toDouble()) * 100).roundToInt()
        } else {
            null
        }
        "▼ ${percent?.let { "$it% " }.orEmpty()}from ${DisplayFormatters.rupees(subject.previousSpent)}"
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
    val sweep = rememberGrowingFraction(score)
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
