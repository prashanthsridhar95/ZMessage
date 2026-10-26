package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CourierStatus
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.displayName

@Composable
internal fun GenericCard(item: ClassifiedMessage, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hasCategory = item.category != MessageCategory.OTHERS

    MessageRow(onClick, modifier, verticalAlignment = Alignment.Top) {
        MerchantChip(item.merchant.logo)
        Spacer(Modifier.width(16.dp))

        Column {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = item.message.sender,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )

            if (hasCategory) CategoryTag(item.category)

            Text(
                modifier = Modifier.padding(top = if (hasCategory) 8.dp else 4.dp).fillMaxWidth(),
                text = item.message.body,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
internal fun CourierCard(
    item: ClassifiedMessage,
    content: MessageContent.Courier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.fillMaxWidth(0.7f)) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = item.message.sender,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
            CategoryTag(MessageCategory.COURIER)
        }

        Text(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            text = when (content.status) {
                CourierStatus.ARRIVING_TODAY -> "Arriving\nToday"
                CourierStatus.DELIVERED -> "Delivered"
                CourierStatus.UPDATE -> "Update"
            },
            maxLines = 2,
            textAlign = TextAlign.End,
            overflow = TextOverflow.Ellipsis,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
internal fun LoyaltyCard(
    item: ClassifiedMessage,
    content: MessageContent.Loyalty,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.Center) {
            Text(
                text = item.merchant.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
            CategoryTag(item.category)
        }

        Column(Modifier.fillMaxWidth()) {
            AmountText(
                text = "${if (content.isLoaded) "+" else "-"} ₹${content.points}",
                color = if (content.isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            IconCaption(R.drawable.wallet_balance, "₹${content.balance}")
        }
    }
}

@Composable
internal fun DataUsageCard(
    content: MessageContent.DataUsage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bottomShape = RoundedCornerShape(bottomEnd = 16.dp, bottomStart = 16.dp)

    Column(modifier.padding(vertical = 8.dp).clickable(onClick = onClick)) {
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
                text = content.carrier.displayName,
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
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
