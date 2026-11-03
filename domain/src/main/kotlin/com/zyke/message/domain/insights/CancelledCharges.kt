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
        val whole = wholePayees(cancelled)
        val usual = usualAmounts(messages, whole, zone)
        val surplus = surplus(messages, cancelled, zone).filter { it.message.date >= since }
        return (candidates.mapNotNull { match(it, whole, usual, zone) } + surplus.mapNotNull { surplusCharge(it, cancelled, zone) })
            .distinctBy { it.message.id }.sortedByDescending { it.message.message.date }
    }

    /** The cancelled keys that stand for a whole payee; a key with a variant stops only one of several alike charges. */
    private fun wholePayees(cancelled: Map<String, LocalDate>): Map<String, LocalDate> =
        cancelled.filterKeys { Subscriptions.VARIANT_SEPARATOR !in it }

    private fun partials(cancelled: Map<String, LocalDate>): Map<String, List<LocalDate>> =
        cancelled.entries.filter { Subscriptions.VARIANT_SEPARATOR in it.key }
            .groupBy({ Subscriptions.payeeOfKey(it.key) }, { it.value })

    private fun month(m: ClassifiedMessage, zone: ZoneId) = day(m, zone).let { it.year * 12 + it.monthValue }

    /**
     * Charges beyond what the payee still has running when only some of its alike standing orders were cancelled: five
     * SIPs a month with two cancelled leave three, so a fourth in a month after the cancelling is one to look at.
     */
    private fun surplus(messages: List<ClassifiedMessage>, cancelled: Map<String, LocalDate>, zone: ZoneId): List<ClassifiedMessage> {
        val partial = partials(cancelled)
        if (partial.isEmpty()) return emptyList()
        val out = ArrayList<ClassifiedMessage>()
        val debits = messages.mapNotNull { m -> debit(m)?.let { tx -> Subscriptions.keyFor(tx.payee) to m } }.groupBy({ it.first }, { it.second })
        for ((payee, days) in partial) {
            val own = debits[payee] ?: continue
            val from = days.min()
            val before = own.filter { !day(it, zone).isAfter(from) }.groupBy { month(it, zone) }
            val usual = before.values.maxOfOrNull { it.size } ?: continue
            val running = usual - days.size
            if (running < 0) continue
            own.filter { day(it, zone).isAfter(from) }.groupBy { month(it, zone) }.values.forEach { inMonth ->
                if (inMonth.size > running) out += inMonth.sortedBy { it.message.date }.drop(running)
            }
        }
        return out
    }

    private fun surplusCharge(m: ClassifiedMessage, cancelled: Map<String, LocalDate>, zone: ZoneId): CancelledCharge? {
        val tx = debit(m) ?: return null
        val payee = Subscriptions.keyFor(tx.payee)
        val on = partials(cancelled)[payee]?.min() ?: return null
        val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        return CancelledCharge(m, payee, m.merchant.name.ifEmpty { tx.payee }, amount, on)
    }

    /** The one message, for a heads-up on arrival; [history] is what is already known. */
    fun check(message: ClassifiedMessage, history: List<ClassifiedMessage>, cancelled: Map<String, LocalDate>, zone: ZoneId): CancelledCharge? {
        if (cancelled.isEmpty()) return null
        val whole = wholePayees(cancelled)
        match(message, whole, usualAmounts(history, whole, zone), zone)?.let { return it }
        return surplus(history + message, cancelled, zone).firstOrNull { it.id == message.id }?.let { surplusCharge(it, cancelled, zone) }
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
