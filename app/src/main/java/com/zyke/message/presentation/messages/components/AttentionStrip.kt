package com.zyke.message.presentation.messages.components

import com.zyke.message.presentation.common.fullWidthClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.movingBorder
import com.zyke.message.presentation.format.DisplayFormatters
import java.time.LocalDate

/**
 * The few things that need you right now, as a plain list with no box or fill of its own.
 *
 * Text and icons use the normal text colour, so contrast is guaranteed by the theme. Urgency is never
 * colour alone: it is said in words ("Due today") and marked with a dot.
 */
@Composable
internal fun AttentionStrip(
    items: List<AttentionItem>,
    today: LocalDate,
    actions: FeedActions,
    modifier: Modifier = Modifier,
    /** Side padding for the heading and rows, inside the border. */
    inset: Dp = 16.dp,
    /** False when a card around the strip draws the border instead. */
    bordered: Boolean = true
) {
    if (items.isEmpty()) return

    Column(
        modifier.fillMaxWidth().then(if (bordered) Modifier.movingBorder(MaterialTheme.colorScheme.primary) else Modifier).padding(vertical = if (bordered) 12.dp else 0.dp)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = inset, vertical = 2.dp),
            text = "Needs your attention",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = inset), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            AttentionRow(item, today, actions, inset)
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, today: LocalDate, actions: FeedActions, inset: Dp) {
    val onContainer = MaterialTheme.colorScheme.onSurface
    val urgent = when (item) {
        is AttentionItem.LowFastTag -> true
        is AttentionItem.BillDue -> item.dueDate?.let { DisplayFormatters.isUrgent(it, today) } ?: false
        is AttentionItem.FreshOtp -> false
    }

    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open message") { actions.onOpenMessage(item.message.id) }
            .padding(start = inset, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            item is AttentionItem.BillDue -> BillActions(payLink, onPay = { payLink?.let(actions.onOpenLink) }, onMarkPaid = { actions.onDismissAttention(item.key) })
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

/**
 * One pill: "Pay" on the left, a divider, and a chevron on the right that opens a menu with "Mark as paid", for a bill
 * that was settled another way. A bill with no pay link is just the "Mark as paid" half.
 */
@Composable
private fun BillActions(payLink: String?, onPay: () -> Unit, onMarkPaid: () -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val container = MaterialTheme.colorScheme.primary
    val content = MaterialTheme.colorScheme.onPrimary
    Box {
        Row(
            Modifier.height(36.dp).clip(shape).background(container),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (payLink != null) {
                Box(
                    Modifier.fillMaxHeight().clickable(onClickLabel = "Pay the bill", onClick = onPay).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Pay", color = content, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
                Box(Modifier.width(1.dp).fillMaxHeight(0.55f).background(content.copy(alpha = 0.35f)))
                Box(
                    Modifier.fillMaxHeight().clickable(onClickLabel = "More bill options") { open = true }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("\u2304", color = content, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            } else {
                Box(
                    Modifier.fillMaxHeight().clickable(onClickLabel = "Mark the bill as paid", onClick = onMarkPaid).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Mark as paid", color = content, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Mark as paid") },
                onClick = { open = false; onMarkPaid() }
            )
        }
    }
}
