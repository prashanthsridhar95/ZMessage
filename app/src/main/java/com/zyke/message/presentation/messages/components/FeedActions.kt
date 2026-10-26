package com.zyke.message.presentation.messages.components

import androidx.compose.runtime.Immutable

/** Everything the list can ask the ViewModel to do, bundled so it can be passed around as one. */
@Immutable
data class FeedActions(
    /** Tap on a card in the timeline: open the sender's conversation at this message. */
    val onOpenMessage: (Long) -> Unit,
    /** Tap on a card inside a conversation: expand it in place. */
    val onToggleExpanded: (Long) -> Unit,
    val onToggleGroup: (String) -> Unit,
    val onCopyOtp: (messageId: Long, code: String) -> Unit,
    val onArchive: (Long) -> Unit,
    val onUnarchive: (Long) -> Unit,
    val onTogglePin: (Long) -> Unit,
    val onDismissAttention: (String) -> Unit,
    val onToggleSummary: () -> Unit,
    val onOpenLink: (String) -> Unit
) {
    companion object {
        val None = FeedActions({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
    }
}
