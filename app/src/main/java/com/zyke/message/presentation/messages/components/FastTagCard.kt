package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.DisplayFormatters

@Composable
internal fun FastTagCard(
    item: ClassifiedMessage,
    content: MessageContent.FastTag,
    time: String,
    isUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val event = content.event

    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo, description = "FASTag", isUnread = isUnread)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            val title = when (event) {
                is FastTagEvent.TollPaid -> event.tollName.ifEmpty { "Toll" }
                is FastTagEvent.ToppedUp -> "FASTag recharge"
                else -> event.vehicleNumber
            }
            RowTitle(title)
            Caption(listOfNotNull(event.vehicleNumber.takeIf { title != it }, time).joinToString(" · "))
        }

        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            when (event) {
                is FastTagEvent.Activated -> AmountText("Active", MaterialTheme.colorScheme.primary)
                is FastTagEvent.RechargeNeeded -> AmountText("Recharge", MaterialTheme.colorScheme.error)
                is FastTagEvent.ToppedUp -> {
                    AmountText("+ ${DisplayFormatters.rupees(event.amount)}", MaterialTheme.colorScheme.primary)
                    Caption("Balance ${DisplayFormatters.rupees(event.balance)}")
                }
                is FastTagEvent.TollPaid -> {
                    AmountText("- ${DisplayFormatters.rupees(event.tollAmount)}", MaterialTheme.colorScheme.secondary)
                    Caption("Balance ${DisplayFormatters.rupees(event.balance)}")
                }
            }
        }
    }
}
