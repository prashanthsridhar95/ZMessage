package com.zyke.message.presentation.messages

import com.zyke.message.domain.conversation.Conversation
import com.zyke.message.domain.conversation.Thread
import com.zyke.message.domain.feed.Feed
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.feed.StatusFilter
import com.zyke.message.domain.feed.TimeWindow
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.UserMessageState
import java.time.Instant

object MessagesContract {

    const val SWIPE_HINT_KEY = "hint-swipe"

    enum class PermissionStatus { UNKNOWN, GRANTED, DENIED }

    enum class ViewMode(val label: String) { TIMELINE("Timeline"), SENDERS("Senders") }

    data class State(
        val permission: PermissionStatus = PermissionStatus.UNKNOWN,
        val isLoading: Boolean = false,
        val hasError: Boolean = false,

        // Inputs
        val messages: List<ClassifiedMessage> = emptyList(),
        val userState: UserMessageState = UserMessageState(),
        val filter: MessageFilter = MessageFilter.All,
        val showFilterSheet: Boolean = false,
        val isSearching: Boolean = false,
        val query: String = "",
        val now: Instant = Instant.EPOCH,
        val viewMode: ViewMode = ViewMode.TIMELINE,
        /** The sender whose conversation is open, if any. */
        val openThreadKey: String? = null,
        /** Message to scroll to and expand when a conversation opens. */
        val focusMessageId: Long? = null,

        // View state
        /** The message whose detail sheet is open. */
        val detailMessageId: Long? = null,
        val expandedGroups: Set<String> = emptySet(),
        val showSummarySheet: Boolean = false,

        /** Everything the list renders, derived from the inputs above. */
        val feed: Feed = Feed.EMPTY,
        /** Senders list, built only while [viewMode] is [ViewMode.SENDERS]. */
        val conversations: List<Conversation> = emptyList(),
        /** The open conversation, if any. */
        val thread: Thread? = null
    ) {
        val selectedCategory: MessageCategory? get() = filter.category

        /** The message behind the open detail sheet, or null if none is open (or it has gone). */
        val detail: ClassifiedMessage? get() = detailMessageId?.let { id -> messages.firstOrNull { it.id == id } }

        val selectedSmartFilter: SmartFilter get() = filter.smart

        /** Gesture hints are shown until the user waves them away. */
        val showSwipeHint: Boolean
            get() = SWIPE_HINT_KEY !in userState.dismissedAttention && feed.visibleCount > 0 && !isSearching

        fun isUnread(message: ClassifiedMessage) =
            !message.message.isRead && message.id !in userState.readIds
    }

    sealed interface Intent {
        /** Sent once the screen is shown, with the current runtime permission state. */
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class PermissionResult(val granted: Boolean) : Intent
        data object RequestPermission : Intent
        /** Try again after the inbox could not be read. */
        data object Retry : Intent
        /** Periodic nudge so time-based things (OTP expiry, due dates) stay current. */
        data object Tick : Intent

        data class SelectSmartFilter(val filter: SmartFilter) : Intent
        data class SelectCategory(val category: MessageCategory?) : Intent
        data class SetViewMode(val mode: ViewMode) : Intent
        /** Tap on a message: show its detail in a bottom sheet. */
        data class OpenMessage(val messageId: Long) : Intent
        data object CloseDetail : Intent
        /** "More from this sender" in the detail sheet: open the conversation, scrolled to the message. */
        data class OpenThreadFor(val messageId: Long) : Intent
        data class OpenSender(val key: String) : Intent
        data object CloseThread : Intent
        data object OpenFilters : Intent
        data object CloseFilters : Intent
        data object ResetFilters : Intent
        /** Clears the sheet's filters but keeps the quick filter along the top. */
        data object ResetExtras : Intent
        data class SelectStatus(val status: StatusFilter) : Intent
        data class SelectWindow(val window: TimeWindow) : Intent
        data object ToggleSearch : Intent
        data class SetQuery(val query: String) : Intent

        data class ToggleGroup(val key: String) : Intent
        data object OpenSummary : Intent
        data object CloseSummary : Intent

        data class CopyOtp(val messageId: Long, val code: String) : Intent
        data class Archive(val messageId: Long) : Intent
        data class Unarchive(val messageId: Long) : Intent
        data class TogglePin(val messageId: Long) : Intent
        /** Mute the sender of this message, or unmute it if it already is muted. */
        data class ToggleMute(val messageId: Long) : Intent
        /** The one-tap clean-up: archive old promotions or old one-time codes together. */
        data class ArchiveMany(val ids: List<Long>) : Intent
        data class UnarchiveMany(val ids: List<Long>) : Intent
        /** File every message with this message's sender / receiver under [category]; null goes back to automatic. */
        data class SetCategory(val messageId: Long, val category: MessageCategory?, val announce: Boolean = true) : Intent
        data class DismissAttention(val key: String) : Intent
        data class OpenLink(val url: String) : Intent
    }

    sealed interface Effect {
        data object LaunchPermissionRequest : Effect
        data class CopyToClipboard(val label: String, val text: String) : Effect
        /** Offer to take back an archive. */
        data class MessageArchived(val messageId: Long) : Effect
        data class ManyArchived(val ids: List<Long>) : Effect
        data class OpenLink(val url: String) : Effect
        /** Say what changed and offer to take it back: [previous] is null if it was automatic before. */
        data class CategoryChanged(
            val messageId: Long,
            val name: String,
            val category: MessageCategory?,
            val previous: MessageCategory?
        ) : Effect
    }
}
