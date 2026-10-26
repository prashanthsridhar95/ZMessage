package com.zyke.message.domain.conversation

import com.zyke.message.domain.classification.MerchantResolver
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.FeedItem
import com.zyke.message.domain.feed.FeedSection
import com.zyke.message.domain.feed.SectionId
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.UserMessageState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Groups the inbox by sender. */
class ConversationBuilder(private val merchants: MerchantResolver = MerchantResolver()) {

    /**
     * @param messages newest first
     * @param query when not blank, keeps senders whose name or any message matches
     * @return senders ordered by their latest message; archived messages are left out
     */
    fun conversations(messages: List<ClassifiedMessage>, userState: UserMessageState, query: String = ""): List<Conversation> {
        val live = messages.filter { it.id !in userState.archivedIds }
        val needle = query.trim()

        return live.groupBy { senderKey(it) }
            .map { (key, group) -> build(key, group, userState) }
            .filter { needle.isEmpty() || matches(it, needle) }
            .sortedByDescending { it.lastEpochMillis }
    }

    /** The conversation with [key], as day sections, newest first. */
    fun thread(messages: List<ClassifiedMessage>, key: String, userState: UserMessageState, zone: ZoneId): Thread? {
        val group = messages.filter { it.id !in userState.archivedIds && senderKey(it) == key }
        if (group.isEmpty()) return null

        val sections = group.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.message.date), zone) }
            .map { (date, items) -> FeedSection(SectionId.Day(date), items.map(FeedItem::Single)) }
        return Thread(build(key, group, userState), sections)
    }

    fun senderKey(message: ClassifiedMessage): String = SenderKey.of(message.message.sender)

    private fun build(key: String, group: List<ClassifiedMessage>, userState: UserMessageState): Conversation {
        val sorted = group.sortedByDescending { it.message.date }
        val address = SenderKey.displayAddress(sorted.first().message.sender)
        return Conversation(
            key = key,
            address = address,
            merchant = merchants.resolve(sorted.first().message.sender),
            messages = sorted,
            unreadCount = sorted.count { !it.message.isRead && it.id !in userState.readIds }
        )
    }

    private fun matches(conversation: Conversation, needle: String) =
        conversation.address.contains(needle, ignoreCase = true) ||
            conversation.merchant.name.contains(needle, ignoreCase = true) ||
            conversation.messages.any { FeedBuilder.matches(it, needle) }
}

object SenderKey {
    // Operator-circle prefix ("AD-", "VM-") and traffic-type suffix ("-S", "-T", "-P", "-G").
    private val HEADER = Regex("^[A-Za-z0-9]{2}-(.+?)(?:-[STPG])?$", RegexOption.IGNORE_CASE)

    /** Same brand sent through different operators or traffic types shares one key. */
    fun of(address: String): String {
        val trimmed = address.trim()
        HEADER.matchEntire(trimmed)?.let { return it.groupValues[1].uppercase() }

        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length >= 10 && trimmed.all { it.isDigit() || it in "+ -" }) digits.takeLast(10) else trimmed.uppercase()
    }

    fun displayAddress(address: String): String {
        val trimmed = address.trim()
        return HEADER.matchEntire(trimmed)?.groupValues?.get(1)?.uppercase() ?: trimmed
    }
}
