package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
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
    // Messages we couldn't place get room to explain themselves; recognised ones stay terse.
    val unrecognised = item.category == MessageCategory.OTHERS

    MessageRow(onClick, modifier, verticalAlignment = Alignment.Top) {
        MerchantChip(item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name, description = null, isUnread = isUnread)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RowTitle(item.message.sender, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Caption(time)
            }
            Caption(
                text = item.message.body,
                modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary,
                maxLines = if (unrecognised) 3 else 1
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
        MerchantChip(item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name, description = item.merchant.name, isUnread = isUnread)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RowTitle(item.message.sender)
            Caption(time)
        }

        Spacer(Modifier.width(12.dp))
        Text(
            text = when (content.status) {
                CourierStatus.ARRIVING_TODAY -> "Arriving\nToday"
                CourierStatus.DELIVERED -> "Delivered"
                CourierStatus.UPDATE -> "Update"
            },
            maxLines = 2,
            textAlign = TextAlign.End,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
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
        MerchantChip(item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name, description = item.merchant.name, isUnread = isUnread)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RowTitle(item.merchant.displayName)
            Caption("Balance ${DisplayFormatters.rupees(content.balance)} · $time")
        }

        Spacer(Modifier.width(12.dp))
        AmountText(
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
    val bottomShape = RoundedCornerShape(bottomEnd = 16.dp, bottomStart = 16.dp)

    val spoken = LocalSpokenSummary.current
    Column(
        modifier
            .padding(vertical = 8.dp)
            .clickable(onClickLabel = "Show message", onClick = onClick)
            .semantics(mergeDescendants = true) { if (spoken != null) contentDescription = spoken }
    ) {
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                modifier = Modifier.padding(vertical = 4.dp),
                text = "${content.carrier.displayName} · $time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .height(72.dp)
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
                    .padding(horizontal = 16.dp),
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
