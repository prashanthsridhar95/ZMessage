package com.zyke.message.domain.insights

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A refund the messages say is on its way, and whether the money has arrived. */
data class RefundItem(
    val message: ClassifiedMessage,
    val from: String,
    val amount: BigDecimal,
    val startedOn: LocalDate,
    val arrivedOn: LocalDate?
) {
    val isPending: Boolean get() = arrivedOn == null
}

/**
 * Keeps track of refunds: a message that says one has been started, matched against a later credit of about the
 * same amount. Until the credit shows up the refund is "on the way".
 */
object RefundTracker {
    private const val SHOW_PENDING_FOR_DAYS = 30L
    private const val SHOW_ARRIVED_FOR_DAYS = 7L
    private val TOLERANCE = BigDecimal("0.02")

    private val STARTED = Regex(
        "(?i)\\brefund\\b.*\\b(?:initiated|processed|approved|will be credited|being processed|in process|raised|issued)\\b"
    )
    private val CREDITED_ALREADY = Regex("(?i)\\b(?:has been|have been|was|is)\\s+credited\\b|(?<!be )\\bcredited to\\b")
    private val AMOUNT = Regex("(?i)(?:INR\\.?|Rs\\.?|₹)\\s*([\\d,]+(?:\\.\\d+)?)")

    /** True for a message that announces a refund which has not arrived yet. */
    fun announcesPendingRefund(body: String): Boolean = STARTED.containsMatchIn(body) && !CREDITED_ALREADY.containsMatchIn(body)

    /** @param messages newest first */
    fun build(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId): List<RefundItem> {
        val today = LocalDate.ofInstant(now, zone)

        val credits = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (!tx.isReceived || tx.isCardPayment) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            Triple(message, amount, Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate())
        }

        val used = HashSet<Long>()
        return messages.asReversed().mapNotNull { message ->
            val started = refundNotice(message) ?: return@mapNotNull null
            val day = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            val match = credits.firstOrNull { (credit, amount, date) ->
                credit.id !in used && !date.isBefore(day) && (amount - started).abs() <= started.multiply(TOLERANCE)
            }?.also { used += it.first.id }
            RefundItem(message, message.merchant.name.ifEmpty { message.message.sender }, started, day, match?.third)
        }.filter { item ->
            if (item.isPending) !item.startedOn.isBefore(today.minusDays(SHOW_PENDING_FOR_DAYS))
            else !item.arrivedOn!!.isBefore(today.minusDays(SHOW_ARRIVED_FOR_DAYS))
        }.sortedByDescending { it.startedOn }
    }

    /** The amount a refund-started message names, or null for any other message. */
    private fun refundNotice(message: ClassifiedMessage): BigDecimal? {
        val content = message.content
        val isNotice = content is MessageContent.Notice && content.kind == NoticeKind.REFUND_PENDING
        if (!isNotice && !announcesPendingRefund(message.message.body)) return null
        return AMOUNT.find(message.message.body)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()
    }
}
