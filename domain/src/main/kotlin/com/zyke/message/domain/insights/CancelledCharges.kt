package com.zyke.message.domain.insights

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.util.toBigDecimalOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A charge that came after the person marked its subscription as cancelled. */
data class CancelledCharge(
    val message: ClassifiedMessage,
    val key: String,
    val name: String,
    val amount: BigDecimal,
    val cancelledOn: LocalDate
) {
    /** What "looks fine" stores for it. */
    val reviewKey: String get() = reviewKey(message)

    companion object {
        fun reviewKey(message: ClassifiedMessage) = "cancelled-charge:${message.id}"
    }
}

object CancelledCharges {
    private val SIZE_TOLERANCE = BigDecimal("0.25")
    private const val ASK_DAYS = 90L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Debits to a payee marked cancelled, on a later day than the mark, of about the size it used to be (any size when
     * no earlier charge is known). Newest first.
     */
    fun find(messages: List<ClassifiedMessage>, cancelled: Map<String, LocalDate>, zone: ZoneId, now: Instant): List<CancelledCharge> {
        if (cancelled.isEmpty()) return emptyList()
        val since = now.toEpochMilli() - ASK_DAYS * DAY_MS
        val candidates = messages.filter { it.message.date >= since }
        val usual = usualAmounts(messages, cancelled, zone)
        return candidates.mapNotNull { match(it, cancelled, usual, zone) }.sortedByDescending { it.message.message.date }
    }

    /** The one message, for a heads-up on arrival; [history] is what is already known. */
    fun check(message: ClassifiedMessage, history: List<ClassifiedMessage>, cancelled: Map<String, LocalDate>, zone: ZoneId): CancelledCharge? {
        if (cancelled.isEmpty()) return null
        return match(message, cancelled, usualAmounts(history, cancelled, zone), zone)
    }

    /** What each cancelled subscription cost: its latest charge on or before the day it was cancelled. */
    private fun usualAmounts(messages: List<ClassifiedMessage>, cancelled: Map<String, LocalDate>, zone: ZoneId): Map<String, BigDecimal> {
        val latest = HashMap<String, Pair<Long, BigDecimal>>()
        for (m in messages) {
            val tx = debit(m) ?: continue
            val key = Subscriptions.keyFor(tx.payee)
            val on = cancelled[key] ?: continue
            if (day(m, zone).isAfter(on)) continue
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
            val seen = latest[key]
            if (seen == null || m.message.date > seen.first) latest[key] = m.message.date to amount
        }
        return latest.mapValues { it.value.second }
    }

    private fun match(m: ClassifiedMessage, cancelled: Map<String, LocalDate>, usual: Map<String, BigDecimal>, zone: ZoneId): CancelledCharge? {
        val tx = debit(m) ?: return null
        val key = Subscriptions.keyFor(tx.payee)
        val on = cancelled[key] ?: return null
        if (!day(m, zone).isAfter(on)) return null
        val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val expected = usual[key]
        if (expected != null && (amount - expected).abs() > expected.multiply(SIZE_TOLERANCE)) return null
        return CancelledCharge(m, key, m.merchant.name.ifEmpty { tx.payee }, amount, on)
    }

    private fun debit(m: ClassifiedMessage): MessageContent.Transaction? {
        if (m.isPromotional || m.isMuted || m.setAside != null) return null
        val tx = m.content as? MessageContent.Transaction ?: return null
        return tx.takeIf { !it.isReceived && !it.isCardPayment && it.payee.isNotBlank() }
    }

    private fun day(m: ClassifiedMessage, zone: ZoneId): LocalDate = Instant.ofEpochMilli(m.message.date).atZone(zone).toLocalDate()
}
