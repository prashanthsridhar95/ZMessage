package com.zyke.message.presentation.messages.components

import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.zyke.message.domain.feed.OtpPolicy
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.MessagesContract.State
import java.time.LocalDate
import java.time.ZoneId

/** Picks the right card for a message (or its expanded detail) and wires swipe actions. */
@Composable
internal fun MessageItem(
    item: ClassifiedMessage,
    state: State,
    actions: FeedActions,
    modifier: Modifier = Modifier
) {
    val isPinned = item.id in state.userState.pinnedIds
    val isArchived = item.id in state.userState.archivedIds
    val isExpanded = item.id in state.expandedMessageIds
    val animated = modifier.animateContentSize()

    if (isExpanded) {
        MessageDetail(item, isPinned, isArchived, actions, animated)
        return
    }

    val card: @Composable () -> Unit = {
        MessageCard(item, state, actions)
    }

    if (state.isSearching) {
        // No swiping while searching: archived matches are listed too, so the gesture would be ambiguous.
        card()
    } else {
        SwipeActions(
            isArchived = isArchived,
            isPinned = isPinned,
            onArchiveOrRestore = { if (isArchived) actions.onUnarchive(item.id) else actions.onArchive(item.id) },
            onTogglePin = { actions.onTogglePin(item.id) },
            modifier = animated,
            content = card
        )
    }
}

@Composable
internal fun MessageCard(
    item: ClassifiedMessage,
    state: State,
    actions: FeedActions,
    modifier: Modifier = Modifier
) {
    val time = remember(item.message.date) { DisplayFormatters.time(item.message.date) }
    val today = remember(state.now) { LocalDate.ofInstant(state.now, ZoneId.systemDefault()) }
    val isUnread = state.isUnread(item)
    val expand = { actions.onToggleExpanded(item.id) }

    when (val content = item.content) {
        is MessageContent.Otp -> OtpCard(
            item, content, time, isUnread,
            isExpired = OtpPolicy.isExpired(item, state.now),
            onExpand = expand,
            onCopy = { actions.onCopyOtp(item.id, it) },
            modifier = modifier
        )
        is MessageContent.FastTag -> FastTagCard(item, content, time, isUnread, expand, modifier)
        is MessageContent.Transaction -> TransactionCard(item, content, time, isUnread, expand, modifier)
        is MessageContent.CardBill -> CardBillCard(item, content, time, isUnread, today, expand, modifier)
        is MessageContent.ScheduledPayment -> ScheduledPaymentCard(item, content, time, isUnread, today, expand, modifier)
        is MessageContent.DataUsage -> DataUsageCard(content, time, expand, modifier)
        is MessageContent.Courier -> CourierCard(item, content, time, isUnread, expand, modifier)
        is MessageContent.Loyalty -> LoyaltyCard(item, content, time, isUnread, expand, modifier)
        MessageContent.Generic -> GenericCard(item, time, isUnread, expand, modifier)
    }
}
