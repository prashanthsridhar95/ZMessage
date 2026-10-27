package com.zyke.message.domain.feed

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserMessageState
import java.time.Duration
import java.time.Instant

/** Old messages that no longer earn a place in the inbox, offered for a one-tap clean-up. */
object Cleanup {
    private val PROMO_AGE = Duration.ofDays(30)
    private val OTP_AGE = Duration.ofDays(1)

    /** Promotions older than 30 days and one-time codes older than a day, never anything pinned or already archived. */
    fun staleIds(messages: List<ClassifiedMessage>, userState: UserMessageState, now: Instant, filter: SmartFilter): List<Long> =
        messages.filter { message ->
            message.id !in userState.archivedIds && message.id !in userState.pinnedIds &&
                when (filter) {
                    SmartFilter.PROMOS -> message.isPromotional && olderThan(message, PROMO_AGE, now)
                    SmartFilter.OTP -> message.content is MessageContent.Otp && olderThan(message, OTP_AGE, now)
                    else -> false
                }
        }.map { it.id }

    private fun olderThan(message: ClassifiedMessage, age: Duration, now: Instant) =
        Duration.between(Instant.ofEpochMilli(message.message.date), now) > age
}
