package com.zyke.message.presentation.messages.components

import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

/** Picks the right card for a message, or its expanded detail. */
@Composable
internal fun MessageItem(
    item: ClassifiedMessage,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCopyOtp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val animated = modifier.animateContentSize()

    if (isExpanded) {
        MessageDetail(item, onToggleExpanded, animated)
        return
    }

    when (val content = item.content) {
        is MessageContent.Otp -> OtpCard(item, content, onToggleExpanded, onCopyOtp, animated)
        is MessageContent.FastTag -> FastTagCard(item, content, onToggleExpanded, animated)
        is MessageContent.Transaction -> TransactionCard(item, content, onToggleExpanded, animated)
        is MessageContent.CardBill -> CardBillCard(item, content, onToggleExpanded, animated)
        is MessageContent.DataUsage -> DataUsageCard(content, onToggleExpanded, animated)
        is MessageContent.Courier -> CourierCard(item, content, onToggleExpanded, animated)
        is MessageContent.Loyalty -> LoyaltyCard(item, content, onToggleExpanded, animated)
        MessageContent.Generic -> GenericCard(item, onToggleExpanded, animated)
    }
}
