package com.zyke.message.domain.feed

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserMessageState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Turns the classified inbox plus the user's choices into what the list shows. */
object FeedBuilder {

    /** @param messages newest first */
    fun build(
        messages: List<ClassifiedMessage>,
        userState: UserMessageState,
        filter: MessageFilter,
        query: String,
        now: Instant,
        zone: ZoneId
    ): Feed {
        val searching = query.isNotBlank()
        val archived = userState.archivedIds
        val live = messages.filter { it.id !in archived }

        val visible = when {
            searching -> messages.filter { matches(it, query) }
            else -> messages.filter { passes(it, filter, userState) }
        }

        val pinned = if (searching) emptyList() else visible.filter { it.id in userState.pinnedIds }
        val rest = visible - pinned.toSet()

        val sections = buildList {
            if (pinned.isNotEmpty()) add(FeedSection(SectionId.Pinned, pinned.map(FeedItem::Single)))
            rest.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.message.date), zone) }
                .forEach { (date, dayMessages) ->
                    val items = if (searching) dayMessages.map(FeedItem::Single) else group(dayMessages, date)
                    add(FeedSection(SectionId.Day(date), items))
                }
        }

        val isAll = !searching && filter == MessageFilter.All
        val showSummary = !searching && (isAll || filter == MessageFilter.Smart(SmartFilter.MONEY))

        return Feed(
            attention = if (isAll) {
                AttentionBuilder.build(live.filter { !it.isPromotional }, userState.dismissedAttention, now, zone)
            } else {
                emptyList()
            },
            summary = if (showSummary) SpendSummaryBuilder.build(live, now, zone) else null,
            sections = sections,
            availableFilters = availableFilters(messages, userState),
            visibleCount = visible.size
        )
    }

    private fun passes(message: ClassifiedMessage, filter: MessageFilter, userState: UserMessageState): Boolean {
        val isArchived = message.id in userState.archivedIds
        return when (filter) {
            is MessageFilter.Category -> !isArchived && message.category == filter.category
            is MessageFilter.Smart -> when (filter.filter) {
                SmartFilter.ARCHIVED -> isArchived
                else -> !isArchived && bucket(message, filter.filter)
            }
        }
    }

    private fun bucket(message: ClassifiedMessage, filter: SmartFilter): Boolean {
        val content = message.content
        return when (filter) {
            SmartFilter.ALL -> !message.isPromotional
            SmartFilter.MONEY -> content is MessageContent.Transaction ||
                content is MessageContent.CardBill || content is MessageContent.Loyalty
            SmartFilter.OTP -> content is MessageContent.Otp
            SmartFilter.BILLS -> content is MessageContent.CardBill
            SmartFilter.TRAVEL -> content is MessageContent.FastTag || content is MessageContent.Courier
            SmartFilter.PROMOS -> message.isPromotional
            SmartFilter.ARCHIVED -> false
        }
    }

    private fun availableFilters(messages: List<ClassifiedMessage>, userState: UserMessageState): List<SmartFilter> {
        val live = messages.filter { it.id !in userState.archivedIds }
        return SmartFilter.entries.filter { filter ->
            when (filter) {
                SmartFilter.ALL -> true
                SmartFilter.ARCHIVED -> messages.any { it.id in userState.archivedIds }
                else -> live.any { bucket(it, filter) }
            }
        }
    }

    /**
     * Collapses repeats within one day: several OTPs from one sender, or repeated
     * data-usage alerts from one carrier, become a single expandable item.
     */
    private fun group(dayMessages: List<ClassifiedMessage>, date: LocalDate): List<FeedItem> {
        val order = LinkedHashMap<String, MutableList<ClassifiedMessage>>()
        val kinds = HashMap<String, GroupKind>()

        for (message in dayMessages) {
            val (key, kind) = when (val content = message.content) {
                is MessageContent.Otp -> "g-$date-otp-${message.message.sender}" to GroupKind.OTP
                is MessageContent.DataUsage -> "g-$date-data-${content.carrier}" to GroupKind.DATA_USAGE
                else -> "m-${message.id}" to null
            }
            order.getOrPut(key) { mutableListOf() }.add(message)
            if (kind != null) kinds[key] = kind
        }

        return order.map { (key, members) ->
            val kind = kinds[key]
            if (kind != null && members.size > 1) FeedItem.Group(key, kind, members) else FeedItem.Single(members.first())
        }
    }

    internal fun matches(message: ClassifiedMessage, query: String): Boolean {
        val needle = query.trim()
        val extra = when (val content = message.content) {
            is MessageContent.Transaction -> "${content.payee} ${content.amount}"
            is MessageContent.CardBill -> content.amount
            is MessageContent.Loyalty -> content.programme
            else -> ""
        }
        return listOf(message.message.body, message.message.sender, message.merchant.name, extra)
            .any { it.contains(needle, ignoreCase = true) }
    }
}
