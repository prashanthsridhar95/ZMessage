package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import java.time.LocalDate

@Composable
internal fun TransactionCard(
    item: ClassifiedMessage,
    content: MessageContent.Transaction,
    time: String,
    isUnread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val account = content.accountNumber.takeIf { it.isNotEmpty() }?.let { "•• $it" }
        ?: content.bankName.displayString.takeIf { it.isNotEmpty() }
    val sign = if (content.isReceived) "+" else "-"

    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo, description = item.merchant.name, isUnread = isUnread)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RowTitle(item.merchant.displayName)
            Caption(listOfNotNull(account, time).joinToString(" · "))
        }

        Spacer(Modifier.width(12.dp))
        AmountText(
            text = "$sign ${DisplayFormatters.rupees(content.amount)}",
            color = if (content.isReceived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
internal fun CardBillCard(
    item: ClassifiedMessage,
    content: MessageContent.CardBill,
    time: String,
    isUnread: Boolean,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bank = content.bankName
    val title = "${bank.displayString} ${if (bank.isCard) "Card " else ""}" +
        if (content.accountNumber.isNotEmpty()) "•• ${content.accountNumber}" else ""
    val due = content.dueDate
    val caption = if (due != null) DisplayFormatters.dueLabel(due, today) else "${BankTransactionType.CARD_BILL.displayString} · $time"
    val urgent = due != null && DisplayFormatters.isUrgent(due, today)

    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo, description = bank.displayString, isUnread = isUnread)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RowTitle(title.trim())
            Caption(caption, color = if (urgent) MaterialTheme.colorScheme.error else mutedColor())
        }

        Spacer(Modifier.width(12.dp))
        AmountText(
            text = DisplayFormatters.rupees(content.amount),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
