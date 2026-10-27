package com.zyke.message.domain.feed

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** The handful of broad buckets shown as chips above the list. */
enum class SmartFilter(val label: String) {
    ALL("All"),
    MONEY("Money"),
    OTP("OTP"),
    BILLS("Bills"),
    TRAVEL("Travel"),
    REMINDERS("Reminders"),
    OTHERS("Others"),
    PROMOS("Promos"),
    ARCHIVED("Archived")
}

enum class StatusFilter(val label: String) {
    ANY("All messages"),
    UNREAD("Unread only"),
    PINNED("Pinned only")
}

enum class TimeWindow(val label: String, val days: Long) {
    ANY("Any time", 0),
    TODAY("Today", 1),
    WEEK("Last 7 days", 7),
    MONTH("Last 30 days", 30)
}

/**
 * What the Inbox is narrowed to. [smart] is the quick filter along the top; the rest are the
 * "extra" filters in the sheet. All of them combine.
 */
data class MessageFilter(
    val smart: SmartFilter = SmartFilter.ALL,
    val category: MessageCategory? = null,
    val status: StatusFilter = StatusFilter.ANY,
    val window: TimeWindow = TimeWindow.ANY
) {
    /** Filters beyond the quick one; these are what the filter button's badge counts. */
    val extraCount: Int
        get() = (if (category != null) 1 else 0) + (if (status != StatusFilter.ANY) 1 else 0) +
            (if (window != TimeWindow.ANY) 1 else 0)

    val hasExtras: Boolean get() = extraCount > 0
    val activeCount: Int get() = extraCount + (if (smart != SmartFilter.ALL) 1 else 0)
    val isActive: Boolean get() = activeCount > 0

    fun withoutExtras() = copy(category = null, status = StatusFilter.ANY, window = TimeWindow.ANY)

    companion object {
        val All = MessageFilter()
    }
}

enum class GroupKind { OTP, DATA_USAGE }

sealed interface FeedItem {
    val key: String

    /** The message to show (for groups: the newest one). */
    val newest: ClassifiedMessage

    data class Single(val message: ClassifiedMessage) : FeedItem {
        override val key get() = "m-${message.id}"
        override val newest get() = message
    }

    /** Several similar messages from one sender on one day, newest first. */
    data class Group(
        override val key: String,
        val kind: GroupKind,
        val messages: List<ClassifiedMessage>
    ) : FeedItem {
        override val newest get() = messages.first()
        val hiddenCount get() = messages.size - 1
    }
}

sealed interface SectionId {
    data object Pinned : SectionId
    data class Day(val date: LocalDate) : SectionId
}

data class FeedSection(val id: SectionId, val items: List<FeedItem>)

/** Something that deserves to be pinned above the feed. */
sealed interface AttentionItem {
    val key: String
    val message: ClassifiedMessage

    data class FreshOtp(
        override val message: ClassifiedMessage,
        val code: String,
        val senderName: String
    ) : AttentionItem {
        override val key get() = "otp-${message.id}"
    }

    data class BillDue(
        override val message: ClassifiedMessage,
        val bankLabel: String,
        val amount: String,
        val dueDate: LocalDate?,
        val paymentLink: String? = null
    ) : AttentionItem {
        override val key get() = "bill-${message.id}"
    }

    data class LowFastTag(
        override val message: ClassifiedMessage,
        val vehicleNumber: String
    ) : AttentionItem {
        override val key get() = "tag-${message.id}"
    }
}

data class CategorySpend(val category: MessageCategory, val amount: BigDecimal)

data class SpendSummary(
    val month: YearMonth,
    val total: BigDecimal,
    val byCategory: List<CategorySpend>
)

data class Feed(
    val attention: List<AttentionItem> = emptyList(),
    val summary: SpendSummary? = null,
    val sections: List<FeedSection> = emptyList(),
    /** Types worth offering in the filter sheet; empty buckets are left out to reduce noise. */
    val availableFilters: List<SmartFilter> = listOf(SmartFilter.ALL),
    /** How many messages each type holds. */
    val counts: Map<SmartFilter, Int> = emptyMap(),
    /** Categories that actually occur, most frequent first. */
    val categories: List<Pair<MessageCategory, Int>> = emptyList(),
    val visibleCount: Int = 0,
    /** How a search was understood, e.g. "Fuel Spends · Last 7 days"; null for plain text searches. */
    val searchNote: String? = null
) {
    companion object {
        val EMPTY = Feed()
    }
}
