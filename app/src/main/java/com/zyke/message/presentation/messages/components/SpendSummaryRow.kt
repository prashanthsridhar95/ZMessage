package com.zyke.message.presentation.messages.components

import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.rememberAnimatedRupees
import com.zyke.message.presentation.common.rememberGrowingFraction
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.feed.SpendSummary
import com.zyke.message.presentation.format.DisplayFormatters

/** "₹18,400 spent in March"; tap for the per-category split in a bottom sheet. */
@Composable
internal fun SpendSummaryRow(
    summary: SpendSummary,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Show spending breakdown", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = "${DisplayFormatters.rupees(summary.total)} spent in ${DisplayFormatters.monthName(summary.month)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Image(
            modifier = Modifier.size(20.dp),
            painter = painterResource(R.drawable.baseline_chevron_right_24),
            colorFilter = ColorFilter.tint(mutedColor()),
            contentDescription = null
        )
    }
}

/** The month's spending split by category, as tall as its content. */
@Composable
internal fun SpendSummarySheet(summary: SpendSummary, onDismiss: () -> Unit) {
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Caption("Spent in ${DisplayFormatters.monthName(summary.month)}")
                Text(
                    text = rememberAnimatedRupees(summary.total),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            summary.byCategory.forEach { spend ->
                val fraction = rememberGrowingFraction((spend.amount.toDouble() / summary.total.toDouble()).toFloat().coerceIn(0f, 1f))
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        RowTitle(spend.category.displayString)
                        RowTitle(DisplayFormatters.rupees(spend.amount))
                    }
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}
