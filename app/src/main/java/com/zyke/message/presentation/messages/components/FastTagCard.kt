package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent

@Composable
internal fun FastTagCard(
    item: ClassifiedMessage,
    content: MessageContent.FastTag,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val event = content.event
    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.Center) {
            Box(Modifier.border(0.5.dp, MaterialTheme.colorScheme.secondary)) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    text = event.vehicleNumber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            CategoryTag(MessageCategory.FASTAG)
        }

        Column(Modifier.fillMaxWidth()) {
            when (event) {
                is FastTagEvent.Activated -> StatusText("ACTIVE", MaterialTheme.colorScheme.primary)
                is FastTagEvent.RechargeNeeded -> StatusText("RECHARGE NOW", MaterialTheme.colorScheme.error)
                is FastTagEvent.ToppedUp -> {
                    AmountText("+ ₹${event.amount}", MaterialTheme.colorScheme.primary)
                    IconCaption(R.drawable.wallet_balance, "₹${event.balance}")
                }
                is FastTagEvent.TollPaid -> {
                    AmountText("- ₹${event.tollAmount}", MaterialTheme.colorScheme.secondary)
                    IconCaption(R.drawable.wallet_balance, "₹${event.balance}")
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        textAlign = TextAlign.End,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color
    )
}
