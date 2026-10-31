package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.messages.components.Caption

/**
 * "This month vs last month" in plain sentences: income, spent, net, savings rate and the biggest category movers
 * over the same number of days. Tapping a mover opens that category's payments.
 */
@Composable
internal fun MonthlySummaryCard(summary: MonthlySummary, onOpenCategory: (MessageCategory) -> Unit) {
    SectionCard(
        "This month vs last month",
        subtitle = "Compared with the same days of last month",
        summary = summary.lines.getOrNull(3)
    ) {
        summary.lines.forEach { line -> Text(line, Modifier.padding(vertical = 2.dp)) }
        if (summary.changes.isEmpty()) {
            Caption("No category has changed yet.", Modifier.padding(top = 8.dp))
        } else {
            Caption("What changed most", Modifier.padding(top = 10.dp, bottom = 4.dp))
            summary.changes.forEachIndexed { index, change ->
                if (index > 0) HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${change.category.displayString}") { onOpenCategory(change.category) }
                        .padding(vertical = 10.dp)
                ) { Text(change.text) }
            }
        }
    }
}
