package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import java.time.LocalDate

/** An auto-debit that is coming up, or a mandate that was just set. Shown muted: no money has moved yet. */
@Composable
internal fun ScheduledPaymentCard(
    item: ClassifiedMessage,
    content: MessageContent.ScheduledPayment,
    time: String,
    isUnread: Boolean,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = if (content.payee.isNotEmpty()) item.merchant.displayName else content.bankName.displayString
    val account = content.accountNumber.takeIf { it.isNotEmpty() }
        ?.let { "${if (content.isCard) "Card" else "A/C"} •• $it" }

    val what = when (content.kind) {
        ScheduledKind.UPCOMING_DEBIT ->
            content.date?.let { "Auto-debit · ${DisplayFormatters.dueLabel(it, today).replaceFirst("Due", "due")}" } ?: "Auto-debit"
        ScheduledKind.MANDATE_SET -> "Autopay set up"
        ScheduledKind.MANDATE_CANCELLED -> "Autopay cancelled"
        ScheduledKind.MANDATE_REQUEST -> "Autopay request · needs your approval"
    }
    val urgent = content.kind == ScheduledKind.UPCOMING_DEBIT && content.date?.let { DisplayFormatters.isUrgent(it, today) } == true

    MessageRow(onClick, modifier) {
        MerchantChip(item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name, description = title, isUnread = isUnread)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RowTitle(title.ifEmpty { "Auto-debit" })
            Caption(
                listOfNotNull(what, account, time.takeIf { content.kind != ScheduledKind.UPCOMING_DEBIT }).joinToString(" · "),
                color = if (urgent) MaterialTheme.colorScheme.error else mutedColor()
            )
        }

        Spacer(Modifier.width(12.dp))
        AmountText(DisplayFormatters.rupees(content.amount), MaterialTheme.colorScheme.secondary)
    }
}
