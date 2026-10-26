package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.displayName

@Composable
internal fun TransactionCard(
    item: ClassifiedMessage,
    content: MessageContent.Transaction,
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
            val sign = if (content.isReceived) "+" else "-"
            AmountText(
                text = "$sign ₹${content.amount}",
                color = if (content.isReceived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )

            when {
                content.accountNumber.isNotEmpty() -> IconCaption(
                    icon = if (content.isCard) R.drawable.credit_card else R.drawable.bank,
                    text = "•• ${content.accountNumber}"
                )
                else -> IconCaption(R.drawable.bank, content.bankName.displayString)
            }
        }
    }
}

@Composable
internal fun CardBillCard(
    item: ClassifiedMessage,
    content: MessageContent.CardBill,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bank = content.bankName
    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.Center) {
            Text(
                text = "${bank.displayString} ${if (bank.isCard) "Card •• " else ""}${content.accountNumber}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = BankTransactionType.CARD_BILL.displayString,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = mutedColor()
            )
        }

        Column(Modifier.fillMaxWidth()) {
            AmountText("₹${content.amount}", MaterialTheme.colorScheme.secondary)
        }
    }
}
