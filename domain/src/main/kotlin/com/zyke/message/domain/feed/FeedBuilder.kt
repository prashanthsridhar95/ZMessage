package com.zyke.message.domain.feed

import com.zyke.message.domain.ledger.PaymentGroups
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

        val pinnedIds = userState.pinnedIds
        // One payment, one row: a bank debit leads the messages that repeat it. Archived and pinned messages stay rows of their own.
        val groups = PaymentGroups.of(messages) { it.id in archived || (!searching && it.id in pinnedIds) }
        val shown = if (groups.isEmpty) visible else foldRelated(visible, messages, groups, filter, searching, userState, today, zone)

        val pinned = if (searching) emptyList() else shown.filter { it.id in pinnedIds }
        val rest = if (pinned.isEmpty()) shown else shown - pinned.toSet()

        val sections = buildList {
            if (pinned.isNotEmpty()) add(FeedSection(SectionId.Pinned, pinned.map { FeedItem.Single(it, groups.followersOf(it.id)) }))
            rest.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.message.date), zone) }
                .forEach { (date, dayMessages) ->
                    val items = if (searching) dayMessages.map { FeedItem.Single(it, groups.followersOf(it.id)) } else group(dayMessages, date, groups)
                    add(FeedSection(SectionId.Day(date), items))
                }
        }

        val isAll = !searching && filter == MessageFilter.All
        val showSummary = !searching && filter.category == null &&
            (filter.smart == SmartFilter.ALL || filter.smart == SmartFilter.MONEY)

        return Feed(
            attention = if (isAll) {
                AttentionBuilder.build(AttentionBuilder.eligible(live), userState.dismissedAttention, now, zone, userState.dismissedAt)
            } else {
                emptyList()
            },
            summary = if (showSummary) SpendSummaryBuilder.build(live, now, zone) else null,
            sections = sections,
            availableFilters = availableFilters(messages, userState, groups),
            counts = counts(messages, userState, groups),
            categories = categoryCounts(messages, userState, groups),
            visibleCount = shown.size,
            searchNote = searchNote
        )
    }

    /**
     * The list with every follower that has its payment in the list left out of it. A search that finds a follower shows its
     * payment instead (the group opens); an "unread" filter keeps a payment whose follower is unread. Newest first, as given.
     */
    private fun foldRelated(
        visible: List<ClassifiedMessage>,
        messages: List<ClassifiedMessage>,
        groups: PaymentGroups,
        filter: MessageFilter,
        searching: Boolean,
        userState: UserMessageState,
        today: LocalDate,
        zone: ZoneId
    ): List<ClassifiedMessage> {
        if (visible.none { groups.isFollower(it.id) }) return visible
        val byId = HashMap<Long, ClassifiedMessage>(messages.size * 2)
        for (m in messages) byId[m.id] = m
        val inList = visible.mapTo(HashSet()) { it.id }
        val keep = HashSet<Long>(inList)
        val unreadOnly = !searching && filter.status == StatusFilter.UNREAD
        val anyStatus = if (unreadOnly) filter.copy(status = StatusFilter.ANY) else filter
        for (f in visible) {
            val anchorId = groups.anchorOf(f.id) ?: continue
            val anchor = byId[anchorId] ?: continue
            when {
                searching -> { keep -= f.id; keep += anchorId }
                anchorId in inList -> keep -= f.id
                unreadOnly && passes(anchor, anyStatus, userState, today, zone) -> { keep -= f.id; keep += anchorId }
            }
        }
        return messages.filter { it.id in keep }
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

    internal fun bucket(message: ClassifiedMessage, filter: SmartFilter): Boolean {
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

    private fun availableFilters(messages: List<ClassifiedMessage>, userState: UserMessageState, groups: PaymentGroups): List<SmartFilter> {
        val counts = counts(messages, userState, groups)
        return SmartFilter.entries.filter { it == SmartFilter.ALL || (counts[it] ?: 0) > 0 }
    }

    /** How many rows each type holds: a payment's followers are inside its row, so they are not counted again where it is. */
    private fun counts(messages: List<ClassifiedMessage>, userState: UserMessageState, groups: PaymentGroups): Map<SmartFilter, Int> {
        val live = messages.filter { it.id !in userState.archivedIds }
        val byId = if (groups.isEmpty) null else live.associateBy { it.id }
        return SmartFilter.entries.associateWith { filter ->
            if (filter == SmartFilter.ARCHIVED) messages.size - live.size
            else live.count { m ->
                bucket(m, filter) && !(byId != null && groups.anchorOf(m.id)?.let { id -> byId[id]?.let { a -> bucket(a, filter) } } == true)
            }
        }
    }

    private fun categoryCounts(messages: List<ClassifiedMessage>, userState: UserMessageState, groups: PaymentGroups): List<Pair<MessageCategory, Int>> {
        val byId = if (groups.isEmpty) null else messages.associateBy { it.id }
        return messages.filter { m ->
            if (m.id in userState.archivedIds || m.isPromotional) return@filter false
            // A follower is inside its payment's row, so it is not counted again under the same category.
            val anchor = byId?.let { index -> groups.anchorOf(m.id)?.let { index[it] } }
            anchor == null || anchor.category != m.category || anchor.id in userState.archivedIds
        }
            .groupingBy { it.category }.eachCount()
            .toList()
            .sortedByDescending { it.second }
    }

    /**
     * Collapses repeats within one day: several OTPs from one sender, or repeated
     * data-usage alerts from one carrier, become a single expandable item.
     */
    private fun group(dayMessages: List<ClassifiedMessage>, date: LocalDate, groups: PaymentGroups): List<FeedItem> {
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
            if (kind != null && members.size > 1) FeedItem.Group(key, kind, members) else members.first().let { FeedItem.Single(it, groups.followersOf(it.id)) }
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
