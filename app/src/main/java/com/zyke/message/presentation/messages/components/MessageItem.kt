package com.zyke.message.presentation.messages.components

import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.zyke.message.domain.feed.OtpPolicy
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.spokenSummary
import com.zyke.message.presentation.messages.MessagesContract.State
import java.time.LocalDate
import java.time.ZoneId

/** A message as one card, with swipe actions (archive / pin). Tapping it opens its detail sheet. */
@Composable
internal fun MessageItem(
    item: ClassifiedMessage,
    state: State,
    actions: FeedActions,
    modifier: Modifier = Modifier
) {
    val isPinned = item.id in state.userState.pinnedIds
    val isArchived = item.id in state.userState.archivedIds
    val card: @Composable () -> Unit = { MessageCard(item, state, actions) }

    if (state.isSearching) {
        // No swiping while searching: archived matches are listed too, so the gesture would be ambiguous.
        card()
    } else {
        SwipeActions(
            isArchived = isArchived,
            isPinned = isPinned,
            onArchiveOrRestore = { if (isArchived) actions.onUnarchive(item.id) else actions.onArchive(item.id) },
            onTogglePin = { actions.onTogglePin(item.id) },
            modifier = modifier.animateContentSize(),
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
    val expand = { actions.onOpenMessage(item.id) }

    val spoken = remember(item, isUnread, time, today) { item.spokenSummary(time, isUnread, today) }

    CompositionLocalProvider(LocalSpokenSummary provides spoken) {
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
}
