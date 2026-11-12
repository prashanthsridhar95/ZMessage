package com.zyke.message.presentation.messages.components

import androidx.compose.runtime.Immutable
import com.zyke.message.domain.model.MessageCategory

/** Everything the list can ask the ViewModel to do, bundled so it can be passed around as one. */
@Immutable
data class FeedActions(
    /** Tap on a card: show the message in a bottom sheet. */
    val onOpenMessage: (Long) -> Unit,
    val onToggleGroup: (String) -> Unit,
    val onCopyOtp: (messageId: Long, code: String) -> Unit,
    val onArchive: (Long) -> Unit,
    val onUnarchive: (Long) -> Unit,
    val onTogglePin: (Long) -> Unit,
    /** Swipe right: mark a row read (true) or unread (false). */
    val onSetRead: (messageId: Long, read: Boolean) -> Unit = { _, _ -> },
    val onDismissAttention: (String) -> Unit,
    /** Settle a bill for good; unlike a dismissal, it does not come back as the due date nears. */
    val onMarkPaid: (String) -> Unit = {},
    val onOpenSummary: () -> Unit,
    val onOpenLink: (String) -> Unit,
    val onSetCategory: (messageId: Long, category: MessageCategory?) -> Unit,
    /** File just this one message under a category, leaving the rest from its sender alone; null goes back to the sender's. */
    val onSetMessageCategory: (messageId: Long, category: MessageCategory?) -> Unit = { _, _ -> },
    /** Mute or unmute the sender of this message. */
    val onToggleMute: (messageId: Long) -> Unit = {},
    /** Say that [duplicateId] is the same transaction as [primaryId]; asks first. */
    val onRequestMerge: (primaryId: Long, duplicateId: Long) -> Unit = { _, _ -> },
    /** Make a merged message a message of its own again. */
    val onUnmerge: (duplicateId: Long) -> Unit = {},
    /** Clear every filter, so the messages they hide are shown again. */
    val onResetFilters: () -> Unit = {},
    /** Start splitting a payment with friends; null where splitting is not offered. */
    val onSplit: ((messageId: Long) -> Unit)? = null
) {
    companion object {
        val None = FeedActions(
            onOpenMessage = {}, onToggleGroup = {}, onCopyOtp = { _, _ -> }, onArchive = {}, onUnarchive = {}, onTogglePin = {},
            onDismissAttention = {}, onOpenSummary = {}, onOpenLink = {}, onSetCategory = { _, _ -> }
        )
    }
}
