package com.zyke.message.presentation.messages.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.feed.SpendSummary
import com.zyke.message.presentation.format.DisplayFormatters

/** "₹18,400 spent in March"; tap for the per-category split. */
@Composable
internal fun SpendSummaryRow(
    summary: SpendSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClickLabel = if (expanded) "Hide spending breakdown" else "Show spending breakdown", onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = "${DisplayFormatters.rupees(summary.total)} spent in ${DisplayFormatters.monthName(summary.month)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(if (expanded) "▲" else "▼", color = mutedColor())
        }

        if (expanded) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                summary.byCategory.forEach { spend ->
                    val fraction = (spend.amount.toDouble() / summary.total.toDouble()).toFloat().coerceIn(0f, 1f)
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Caption(spend.category.displayString, color = MaterialTheme.colorScheme.secondary)
                            Caption(DisplayFormatters.rupees(spend.amount), color = MaterialTheme.colorScheme.secondary)
                        }
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }
}
