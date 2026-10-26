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
    PROMOS("Promos"),
    ARCHIVED("Archived")
}

/** What the Inbox is narrowed to. The two parts combine: e.g. Money in the Food category. */
data class MessageFilter(
    val smart: SmartFilter = SmartFilter.ALL,
    val category: MessageCategory? = null
) {
    val activeCount: Int get() = (if (smart != SmartFilter.ALL) 1 else 0) + (if (category != null) 1 else 0)
    val isActive: Boolean get() = activeCount > 0

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
    val visibleCount: Int = 0
) {
    companion object {
        val EMPTY = Feed()
    }
}
