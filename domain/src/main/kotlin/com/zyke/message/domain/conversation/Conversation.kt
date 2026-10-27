package com.zyke.message.domain.conversation

import com.zyke.message.domain.feed.FeedSection
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant

/** Everything one sender has sent, newest first. */
data class Conversation(
    val key: String,
    /** "HDFCBK" for "AD-HDFCBK"; the number itself for phone numbers. */
    val address: String,
    /** Brand taken from the sender id, or a plain person-style placeholder. */
    val merchant: Merchant,
    val messages: List<ClassifiedMessage>,
    val unreadCount: Int
) {
    val newest: ClassifiedMessage get() = messages.first()
    val lastEpochMillis: Long get() = newest.message.date
}

/** The open conversation, split into day sections. */
data class Thread(val conversation: Conversation, val sections: List<FeedSection>)
