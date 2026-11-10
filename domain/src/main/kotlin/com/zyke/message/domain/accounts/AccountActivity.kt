package com.zyke.message.domain.accounts

import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.util.toBigDecimalOrNull
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
        val inPeriod = messages.filter { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@filter false
            if (tx.accountNumber.filter(Char::isDigit).takeLast(4) != last4 || tx.isCard != isCard) return@filter false
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            !date.isBefore(from) && !date.isAfter(to) && tx.amount.toBigDecimalOrNull() != null
        }
        // A second bank alert of a payment already listed here is inside that payment's row: it is neither listed nor added again.
        val ids = inPeriod.mapTo(HashSet()) { it.id }
        val list = inPeriod.filter { message ->
            val tx = message.content as MessageContent.Transaction
            val amount = tx.amount.toBigDecimalOrNull() ?: return@filter false
            if (SamePayment.isRepeat(message) && message.samePayment?.primaryId in ids) return@filter false
            // Still listed, but a payment left out of the totals, or a transfer, is not counted in what was spent or received.
            if (message.setAside == null && !SamePayment.isRepeat(message)) { if (tx.isReceived) received += amount else spent += amount }
            true
        }.sortedByDescending { it.message.date }
        return AccountActivity(spent, received, list)
    }
}
