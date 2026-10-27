package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.Image
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
 * The report card in one tappable row: the overall grade and what stands out. The full marks open in a sheet.
 *
 * Every grade is a letter, so the meaning never rests on colour; the colours only echo it, and each pair
 * is a container with its own "on" colour so the letter stays readable in both themes.
 */
@Composable
internal fun ReportCardSummary(card: ReportCard, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable(onClickLabel = "Open report card", onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GradeBadge(card.overall, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            RowTitle("Report card · ${DisplayFormatters.monthName(card.month)}")
            Caption(remarkText(card.remark), Modifier.padding(top = 2.dp), maxLines = 2)
        }
        Image(
            modifier = Modifier.size(20.dp),
            painter = painterResource(R.drawable.baseline_chevron_right_24),
            colorFilter = ColorFilter.tint(mutedColor()),
            contentDescription = null
        )
    }
}

/** This month's marks in full: overall grade, a sentence about it, and a mark per category. */
@Composable
internal fun ReportCardSheet(card: ReportCard, onDismiss: () -> Unit) {
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)
        ) {
            Text(
                text = "Report card · ${DisplayFormatters.monthName(card.month)}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Caption("This month so far, against the same days last month", Modifier.padding(top = 2.dp, bottom = 16.dp))

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
