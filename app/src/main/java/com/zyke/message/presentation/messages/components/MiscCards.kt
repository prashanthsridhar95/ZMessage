package com.zyke.message.presentation.messages.components

import com.zyke.message.presentation.theme.Spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.theme.Radius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CourierStatus
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.SenderTitle
import com.zyke.message.presentation.common.rememberSenderLabel
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName

@Composable
internal fun GenericCard(
    item: ClassifiedMessage,
    time: String,
    isUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo, size = RowTile, imageKey = item.merchant.imageKey, name = item.merchant.name, description = null, isUnread = isUnread)
        Spacer(Modifier.width(Spacing.lg))

        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SenderTitle(rememberSenderLabel(item.message.sender, item.merchant.name.takeIf { item.merchant.renamed }), Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.sm))
                SourceTag(item.message)
                if (item.message.isMms || item.message.isChat()) Spacer(Modifier.width(Spacing.xs + 2.dp))
                Caption(time)
            }
            item.samePayment?.takeIf { it.kind == com.zyke.message.domain.ledger.SamePaymentKind.CERTAIN }?.let {
                Caption("Same payment as ${it.primaryLabel}", color = MaterialTheme.colorScheme.primary)
            }
            // One line, like every other row; the whole text is a tap away.
            Caption(
                text = item.message.body.replace(Regex("\\s+"), " "),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
internal fun CourierCard(
    item: ClassifiedMessage,
    content: MessageContent.Courier,
    time: String,
    isUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo, size = RowTile, imageKey = item.merchant.imageKey, name = item.merchant.name, description = item.merchant.name, isUnread = isUnread)
        Spacer(Modifier.width(Spacing.lg))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            SenderTitle(rememberSenderLabel(item.message.sender, item.merchant.name.takeIf { item.merchant.renamed }))
            Caption(time)
        }

        Spacer(Modifier.width(Spacing.md))
        Text(
            text = when (content.status) {
                CourierStatus.ARRIVING_TODAY -> "Arriving today"
                CourierStatus.DELIVERED -> "Delivered"
                CourierStatus.UPDATE -> "Update"
            },
            modifier = Modifier.widthIn(max = 140.dp),
            maxLines = 1,
            textAlign = TextAlign.End,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
internal fun LoyaltyCard(
    item: ClassifiedMessage,
    content: MessageContent.Loyalty,
    time: String,
    isUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo, size = RowTile, imageKey = item.merchant.imageKey, name = item.merchant.name, description = item.merchant.name, isUnread = isUnread)
        Spacer(Modifier.width(Spacing.lg))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RowTitle(item.merchant.displayName)
            Caption("Balance ${DisplayFormatters.rupees(content.balance)} · $time")
        }

        Spacer(Modifier.width(Spacing.md))
        RowAmount(
            text = "${if (content.isLoaded) "+" else "-"} ${DisplayFormatters.rupees(content.points)}",
            color = if (content.isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
internal fun DataUsageCard(
    content: MessageContent.DataUsage,
    time: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bottomShape = RoundedCornerShape(bottomEnd = Radius.control, bottomStart = Radius.control)

    val spoken = LocalSpokenSummary.current
    val swipeActions = LocalRowCustomActions.current
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .padding(vertical = Spacing.sm)
            .pressFeedback(source)
            .clickable(
                interactionSource = source, indication = LocalIndication.current,
                role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Show message", onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                if (spoken != null) contentDescription = spoken
                if (swipeActions.isNotEmpty()) customActions = swipeActions
            }
    ) {
        Row(
            Modifier
                .padding(horizontal = Spacing.lg)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    RoundedCornerShape(topStart = Radius.control, topEnd = Radius.control)
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                modifier = Modifier.padding(vertical = Spacing.xs),
                text = "${content.carrier.displayName} · $time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Row(
            Modifier
                .padding(horizontal = Spacing.lg)
                .heightIn(min = 72.dp)
                .fillMaxWidth()
                .clip(bottomShape)
                .border(1.dp, MaterialTheme.colorScheme.secondaryContainer, bottomShape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .fillMaxHeight()
                    .fillMaxWidth(content.percentUsed.coerceIn(0, 100) / 100f)
                    .padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "${content.percentUsed}%",
                    fontSize = 32.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Data usage alert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
