package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.presentation.format.DisplayFormatters
import java.time.LocalDate

/** The few things that need you right now, pinned above the feed. */
@Composable
internal fun AttentionStrip(
    items: List<AttentionItem>,
    today: LocalDate,
    actions: FeedActions,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            AttentionCard(item, today, actions)
        }
    }
}

@Composable
private fun AttentionCard(item: AttentionItem, today: LocalDate, actions: FeedActions) {
    val container = when {
        item is AttentionItem.LowFastTag -> MaterialTheme.colorScheme.errorContainer
        item is AttentionItem.BillDue && item.dueDate != null && DisplayFormatters.isUrgent(item.dueDate, today) ->
            MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            when (item) {
                is AttentionItem.FreshOtp -> {
                    OtpCode(item.code)
                    Caption("${item.senderName} · valid for a few minutes", Modifier.padding(top = 4.dp))
                }
                is AttentionItem.BillDue -> {
                    RowTitle("${DisplayFormatters.rupees(item.amount)} · ${item.bankLabel}")
                    Caption(item.dueDate?.let { DisplayFormatters.dueLabel(it, today) } ?: "Bill generated")
                }
                is AttentionItem.LowFastTag -> {
                    RowTitle("Low FASTag balance")
                    Caption(item.vehicleNumber)
                }
            }
        }

        when {
            item is AttentionItem.FreshOtp -> FilledTonalButton(onClick = { actions.onCopyOtp(item.message.id, item.code) }) {
                Text("Copy")
            }
            item is AttentionItem.BillDue && item.paymentLink != null -> FilledTonalButton(
                onClick = { actions.onOpenLink(item.paymentLink) }
            ) { Text("Pay") }
        }

        Box(
            Modifier.size(48.dp).clickable(onClickLabel = "Dismiss") { actions.onDismissAttention(item.key) },
            contentAlignment = Alignment.Center
        ) {
            Image(
                modifier = Modifier.size(18.dp),
                painter = painterResource(R.drawable.baseline_close_24),
                colorFilter = ColorFilter.tint(mutedColor()),
                contentDescription = "Dismiss"
            )
        }
    }
}

