package com.zyke.message.presentation.dashboard.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * This month as a report card: an overall grade, one sentence about it, and a mark per category.
 *
 * Every grade is a letter, so the meaning never rests on colour; the colours only echo it, and each pair
 * is a container with its own "on" colour so the letter stays readable in both themes.
 */
@Composable
internal fun ReportCardSection(card: ReportCard, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Report card · ${DisplayFormatters.monthName(card.month)}",
        subtitle = "This month so far, against the same days last month",
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradeBadge(card.overall, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(overallLine(card))
                Caption(remarkText(card.remark), Modifier.padding(top = 2.dp), maxLines = 3)
            }
        }

        if (card.subjects.isNotEmpty()) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                card.subjects.forEach { SubjectRow(it) }
            }
        }

        if (card.unreadMessages > 0) {
            Caption(
                "${DisplayFormatters.count(card.unreadMessages, "money message", "money messages")} couldn't be read, " +
                    "so these marks may be kinder than the truth.",
                Modifier.padding(top = 12.dp),
                maxLines = 3
            )
        }
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

@Composable
private fun SubjectRow(subject: Subject) {
    val budget = subject.budget
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GradeBadge(subject.grade, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            RowTitle(subject.category.displayString)
            Caption(
                buildString {
                    append(DisplayFormatters.rupees(subject.spent))
                    if (budget != null) append(" of ${DisplayFormatters.rupees(budget)} budget")
                    append(" · ")
                    append(trendText(subject))
                },
                maxLines = 2
            )
        }
    }
}

private fun trendText(subject: Subject): String = when (subject.trend) {
    Trend.UP -> "▲ from ${DisplayFormatters.rupees(subject.previousSpent)}"
    Trend.DOWN -> "▼ from ${DisplayFormatters.rupees(subject.previousSpent)}"
    Trend.SAME -> "same as last month"
    Trend.NEW -> "new this month"
}

/** A letter in a rounded square; a dash when there is nothing to grade yet. */
@Composable
private fun GradeBadge(grade: Grade?, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (grade) {
        Grade.A, Grade.B -> colors.primaryContainer to colors.onPrimaryContainer
        Grade.C -> colors.secondaryContainer to colors.onSecondaryContainer
        Grade.D, Grade.F -> colors.errorContainer to colors.onErrorContainer
        null -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Box(
        Modifier.size(size).background(container, RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = grade?.label ?: "–",
            style = if (size >= 56.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = content
        )
    }
}
