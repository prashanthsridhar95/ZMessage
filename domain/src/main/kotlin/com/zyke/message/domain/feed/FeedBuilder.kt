package com.zyke.message.domain.feed

import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageCategory
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

        val today = LocalDate.ofInstant(now, zone)
        val interpreted = if (searching) QueryInterpreter.interpret(query) else null
        val smartResults = interpreted?.takeIf { it.hasFilters }?.let { found ->
            messages.filter { passes(it, found.filter, userState, today, zone) && (found.text.isBlank() || matches(it, found.text)) }
        }

        // "fuel this week" works as filters; when that finds nothing the words are searched as plain text.
        val visible = when {
            searching && !smartResults.isNullOrEmpty() -> smartResults
            searching -> messages.filter { matches(it, query) }
            else -> messages.filter { passes(it, filter, userState, today, zone) }
        }
        val searchNote = if (searching && !smartResults.isNullOrEmpty()) interpreted?.note else null

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
        val showSummary = !searching && filter.category == null &&
            (filter.smart == SmartFilter.ALL || filter.smart == SmartFilter.MONEY)

        return Feed(
            attention = if (isAll) {
                AttentionBuilder.build(live.filter { !it.isPromotional }, userState.dismissedAttention, now, zone, userState.dismissedAt)
            } else {
                emptyList()
            },
            summary = if (showSummary) SpendSummaryBuilder.build(live, now, zone) else null,
            sections = sections,
            availableFilters = availableFilters(messages, userState),
            counts = counts(messages, userState),
            categories = categoryCounts(messages, userState),
            visibleCount = visible.size,
            searchNote = searchNote
        )
    }

    private fun passes(
        message: ClassifiedMessage,
        filter: MessageFilter,
        userState: UserMessageState,
        today: LocalDate,
        zone: ZoneId
    ): Boolean {
        val isArchived = message.id in userState.archivedIds
        if (isFastTagStatusOnly(message)) return false
        if (filter.category != null && message.category != filter.category) return false

        val statusOk = when (filter.status) {
            StatusFilter.ANY -> true
            StatusFilter.UNREAD -> !message.message.isRead && message.id !in userState.readIds
            StatusFilter.PINNED -> message.id in userState.pinnedIds
        }
        if (!statusOk) return false

        if (filter.window != TimeWindow.ANY) {
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(message.message.date), zone)
            val earliest = when (filter.window) {
                TimeWindow.TODAY -> today
                TimeWindow.WEEK -> today.minusDays(TimeWindow.WEEK.days - 1)
                TimeWindow.MONTH -> today.minusDays(TimeWindow.MONTH.days - 1)
                TimeWindow.ANY -> date
            }
            if (date.isBefore(earliest)) return false
        }

        return if (filter.smart == SmartFilter.ARCHIVED) isArchived else !isArchived && bucket(message, filter.smart)
    }

    /**
     * "FASTag active" and "recharge needed" say nothing a row can act on by itself; low balance is raised
     * in the attention strip instead, and search still finds them.
     */
    private fun isFastTagStatusOnly(message: ClassifiedMessage): Boolean {
        val event = (message.content as? MessageContent.FastTag)?.event
        return event is FastTagEvent.Activated || event is FastTagEvent.RechargeNeeded
    }

    private fun bucket(message: ClassifiedMessage, filter: SmartFilter): Boolean {
        val content = message.content
        return when (filter) {
            SmartFilter.ALL -> !message.isPromotional
            SmartFilter.MONEY -> content is MessageContent.Transaction ||
                content is MessageContent.CardBill || content is MessageContent.Loyalty ||
                content is MessageContent.ScheduledPayment
            SmartFilter.OTP -> content is MessageContent.Otp
            SmartFilter.BILLS -> content is MessageContent.CardBill
            SmartFilter.TRAVEL -> (content is MessageContent.FastTag && !isFastTagStatusOnly(message)) ||
                content is MessageContent.Courier ||
                (content is MessageContent.Life && (content.kind == LifeKind.TRAVEL || content.kind == LifeKind.DELIVERY))
            SmartFilter.REMINDERS -> content is MessageContent.Life &&
                (content.kind == LifeKind.APPOINTMENT || content.kind == LifeKind.RENEWAL)
            // Everything that did not land in a category, other than marketing.
            SmartFilter.OTHERS -> message.category == MessageCategory.OTHERS && !message.isPromotional
            SmartFilter.PROMOS -> message.isPromotional
            SmartFilter.ARCHIVED -> false
        }
    }

    private fun availableFilters(messages: List<ClassifiedMessage>, userState: UserMessageState): List<SmartFilter> {
        val counts = counts(messages, userState)
        return SmartFilter.entries.filter { it == SmartFilter.ALL || (counts[it] ?: 0) > 0 }
    }

    private fun counts(messages: List<ClassifiedMessage>, userState: UserMessageState): Map<SmartFilter, Int> {
        val live = messages.filter { it.id !in userState.archivedIds }
        return SmartFilter.entries.associateWith { filter ->
            if (filter == SmartFilter.ARCHIVED) messages.size - live.size else live.count { bucket(it, filter) }
        }
    }

    private fun categoryCounts(messages: List<ClassifiedMessage>, userState: UserMessageState) =
        messages.filter { it.id !in userState.archivedIds && !it.isPromotional }
            .groupingBy { it.category }.eachCount()
            .toList()
            .sortedByDescending { it.second }

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
            is MessageContent.ScheduledPayment -> "${content.payee} ${content.amount}"
            is MessageContent.Life -> "${content.title} ${content.status} ${content.reference.orEmpty()}"
            else -> ""
        }
        return listOf(message.message.body, message.message.sender, message.merchant.name, extra)
            .any { it.contains(needle, ignoreCase = true) }
    }
}
