package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.format.DisplayFormatters
import java.time.LocalDate

/**
 * The few things that need you right now, as one calm card.
 *
 * Colour is handled in one place: the whole card is a single container colour and every piece of text
 * and every icon uses the matching "on" colour, so contrast is guaranteed by the theme. Urgency is never
 * colour alone: it is said in words ("Due today") and marked with a dot.
 */
@Composable
internal fun AttentionStrip(
    items: List<AttentionItem>,
    today: LocalDate,
    actions: FeedActions,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(top = 14.dp, bottom = 4.dp)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            text = "NEEDS YOUR ATTENTION",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = onContainer.copy(alpha = 0.8f)
        )
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = onContainer.copy(alpha = 0.15f))
            AttentionRow(item, today, actions)
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, today: LocalDate, actions: FeedActions) {
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val urgent = when (item) {
        is AttentionItem.LowFastTag -> true
        is AttentionItem.BillDue -> item.dueDate?.let { DisplayFormatters.isUrgent(it, today) } ?: false
        is AttentionItem.FreshOtp -> false
    }

    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (urgent) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Spacer(Modifier.width(10.dp))
        }

        Column(Modifier.weight(1f)) {
            val (title, detail) = when (item) {
                is AttentionItem.FreshOtp -> item.code to "${item.senderName} · valid for a few minutes"
                is AttentionItem.BillDue ->
                    "${DisplayFormatters.rupees(item.amount)} · ${item.bankLabel}" to
                        (item.dueDate?.let { DisplayFormatters.dueLabel(it, today) } ?: "Bill generated")
                is AttentionItem.LowFastTag -> "Low FASTag balance" to item.vehicleNumber
            }
            Text(
                text = title,
                style = if (item is AttentionItem.FreshOtp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = if (item is AttentionItem.FreshOtp) 4.sp else 0.sp,
                color = onContainer,
                maxLines = 1
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.8f),
                fontWeight = if (urgent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2
            )
        }

        val payLink = (item as? AttentionItem.BillDue)?.paymentLink
        when {
            item is AttentionItem.FreshOtp ->
                AppButton("Copy", { actions.onCopyOtp(item.message.id, item.code) }, size = ButtonSize.COMPACT)
            payLink != null ->
                AppButton("Pay", { actions.onOpenLink(payLink) }, size = ButtonSize.COMPACT)
        }

        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Dismiss") { actions.onDismissAttention(item.key) },
            contentAlignment = Alignment.Center
        ) {
            Image(
                modifier = Modifier.size(18.dp),
                painter = painterResource(R.drawable.baseline_close_24),
                colorFilter = ColorFilter.tint(onContainer.copy(alpha = 0.8f)),
                contentDescription = "Dismiss"
            )
        }
    }
}
