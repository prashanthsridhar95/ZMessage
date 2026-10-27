package com.zyke.message.domain.feed

import com.zyke.message.domain.model.ClassifiedMessage
import java.time.Duration
import java.time.Instant

object OtpPolicy {
    val VALIDITY: Duration = Duration.ofMinutes(10)

    fun isExpired(message: ClassifiedMessage, now: Instant): Boolean =
        Duration.between(Instant.ofEpochMilli(message.message.date), now) > VALIDITY
}
