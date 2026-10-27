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
    val onDismissAttention: (String) -> Unit,
    val onOpenSummary: () -> Unit,
    val onOpenLink: (String) -> Unit,
    val onSetCategory: (messageId: Long, category: MessageCategory?) -> Unit
) {
    companion object {
        val None = FeedActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, { _, _ -> })
    }
}
