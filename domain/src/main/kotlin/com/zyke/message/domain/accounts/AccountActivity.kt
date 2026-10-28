package com.zyke.message.domain.accounts

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What went through one account or card in a stretch of days: the payments, newest first, and their totals. */
data class AccountActivity(
    val spent: BigDecimal,
    val received: BigDecimal,
    val messages: List<ClassifiedMessage>
)

object AccountActivities {
    /** The transactions of the account ending [last4] dated from [from] to [to], both included. */
    fun build(
        messages: List<ClassifiedMessage>, last4: String, isCard: Boolean, from: LocalDate, to: LocalDate, zone: ZoneId
    ): AccountActivity {
        var spent = BigDecimal.ZERO
        var received = BigDecimal.ZERO
        val list = messages.filter { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@filter false
            if (tx.accountNumber.filter(Char::isDigit).takeLast(4) != last4 || tx.isCard != isCard) return@filter false
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (date.isBefore(from) || date.isAfter(to)) return@filter false
            val amount = tx.amount.toBigDecimalOrNull() ?: return@filter false
            if (tx.isReceived) received += amount else spent += amount
            true
        }.sortedByDescending { it.message.date }
        return AccountActivity(spent, received, list)
    }
}
