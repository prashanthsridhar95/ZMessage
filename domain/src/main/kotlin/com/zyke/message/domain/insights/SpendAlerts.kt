package com.zyke.message.domain.insights

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale

enum class AlertKind { CHARGED_TWICE, UNUSUALLY_HIGH, AUTOPAY_CHANGED }

/** Something in the recent messages worth a second look. */
data class SpendAlert(val message: ClassifiedMessage, val kind: AlertKind, val text: String)

/** Finds odd-looking money movements from the person's own history. Plain statistics, computed locally. */
object SpendAlerts {
    private val RECENT = Duration.ofDays(14)
    private val DUPLICATE_WINDOW = Duration.ofMinutes(30)
    private const val MIN_HISTORY = 3
    private const val HIGH_MULTIPLE = 3
    private val MIN_HIGH_AMOUNT = BigDecimal(500)
    private const val MAX_ALERTS = 4

    /** @param messages newest first */
    fun find(messages: List<ClassifiedMessage>, nowMillis: Long): List<SpendAlert> {
        val cutoff = nowMillis - RECENT.toMillis()
        val payments = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            if (tx.isReceived || tx.payee.isBlank()) null else Payment(message, tx.payee.trim().lowercase(), amount)
        }
        val alerts = mutableListOf<SpendAlert>()

        payments.filter { it.message.message.date >= cutoff }.forEach { payment ->
            val twin = payments.firstOrNull {
                it.message.id != payment.message.id && it.payee == payment.payee && it.amount.compareTo(payment.amount) == 0 &&
                    kotlin.math.abs(it.message.message.date - payment.message.message.date) <= DUPLICATE_WINDOW.toMillis()
            }
            // Flag the later of the pair only.
            if (twin != null && twin.message.message.date < payment.message.message.date) {
                alerts += SpendAlert(payment.message, AlertKind.CHARGED_TWICE, "${label(payment)} was charged twice: ${rupees(payment.amount)}")
            } else if (isUnusuallyHigh(payment, payments)) {
                alerts += SpendAlert(payment.message, AlertKind.UNUSUALLY_HIGH, "${rupees(payment.amount)} at ${label(payment)} is well above your usual")
            }
        }

        alerts += autopayChanges(messages, cutoff)
        return alerts.distinctBy { it.message.id }.sortedByDescending { it.message.message.date }.take(MAX_ALERTS)
    }

    private fun isUnusuallyHigh(payment: Payment, all: List<Payment>): Boolean {
        val earlier = all.filter { it.payee == payment.payee && it.message.message.date < payment.message.message.date }
        if (earlier.size < MIN_HISTORY || payment.amount < MIN_HIGH_AMOUNT) return false
        val sorted = earlier.map { it.amount }.sorted()
        val median = sorted[sorted.size / 2]
        return median.signum() > 0 && payment.amount >= median.multiply(BigDecimal(HIGH_MULTIPLE))
    }

    private fun autopayChanges(messages: List<ClassifiedMessage>, cutoff: Long): List<SpendAlert> {
        val scheduled = messages.mapNotNull { message ->
            val content = message.content as? MessageContent.ScheduledPayment ?: return@mapNotNull null
            if (content.kind != ScheduledKind.UPCOMING_DEBIT && content.kind != ScheduledKind.MANDATE_SET) return@mapNotNull null
            val amount = content.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            Triple(message, content.payee.trim().lowercase(), amount)
        }
        return scheduled.filter { it.first.message.date >= cutoff && it.second.isNotEmpty() }.mapNotNull { (message, payee, amount) ->
            val before = scheduled.firstOrNull { it.second == payee && it.first.message.date < message.message.date }
            if (before != null && before.third.compareTo(amount) != 0) {
                val content = message.content as MessageContent.ScheduledPayment
                SpendAlert(message, AlertKind.AUTOPAY_CHANGED, "${content.payee} autopay changed from ${rupees(before.third)} to ${rupees(amount)}")
            } else {
                null
            }
        }
    }

    private class Payment(val message: ClassifiedMessage, val payee: String, val amount: BigDecimal)

    private fun label(payment: Payment) = (payment.message.content as MessageContent.Transaction).payee.trim()

    private fun rupees(amount: BigDecimal) =
        "₹" + NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply { maximumFractionDigits = 2 }.format(amount)
}
