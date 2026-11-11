package com.zyke.message.presentation.messages.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
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
    modifier: Modifier = Modifier,
    /** The messages that repeat this payment (see PaymentGroups): shown inside this row, never as rows of their own. */
    related: List<ClassifiedMessage> = emptyList()
) {
    val isPinned = item.id in state.userState.pinnedIds
    val isArchived = item.id in state.userState.archivedIds
    val card: @Composable () -> Unit = { MessageCard(item, state, actions, related = related) }

    val mergeable = item.content is MessageContent.Transaction && !state.isSearching
    val drag = LocalMergeDrag.current

    Box(Modifier.mergeDraggable(item.id, drag, mergeable) { primary, duplicate -> actions.onRequestMerge(primary, duplicate) }) {
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
}

@Composable
internal fun MessageCard(
    item: ClassifiedMessage,
    state: State,
    actions: FeedActions,
    modifier: Modifier = Modifier,
    related: List<ClassifiedMessage> = emptyList()
) {
    val time = remember(item.message.date) { DisplayFormatters.time(item.message.date) }
    val today = remember(state.now) { LocalDate.ofInstant(state.now, ZoneId.systemDefault()) }
    // A payment with a repeat nobody has read yet is unread, as a whole.
    val isUnread = state.isUnread(item) || related.any { state.isUnread(it) }
    val expand = { actions.onOpenMessage(item.id) }

    val spoken = com.zyke.message.presentation.common.privateSpeech(remember(item, isUnread, time, today, related.size) { item.spokenSummary(time, isUnread, today, related.size) })

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
            is MessageContent.Transaction -> TransactionCard(
                item, content, time, isUnread, expand, modifier,
                relatedCount = related.size,
                relatedLabel = remember(related) { com.zyke.message.domain.ledger.PaymentGroups.relatedCaption(related) }
            )
            is MessageContent.CardBill -> CardBillCard(item, content, time, isUnread, today, expand, modifier)
            is MessageContent.ScheduledPayment -> ScheduledPaymentCard(item, content, time, isUnread, today, expand, modifier)
            is MessageContent.DataUsage -> DataUsageCard(content, time, expand, modifier)
            is MessageContent.Courier -> CourierCard(item, content, time, isUnread, expand, modifier)
            is MessageContent.Loyalty -> LoyaltyCard(item, content, time, isUnread, expand, modifier)
            is MessageContent.Life -> LifeCard(item, content, time, isUnread, today, expand, modifier)
            is MessageContent.Notice -> GenericCard(item, time, isUnread, expand, modifier)
            MessageContent.Generic -> GenericCard(item, time, isUnread, expand, modifier)
        }
    }
}
