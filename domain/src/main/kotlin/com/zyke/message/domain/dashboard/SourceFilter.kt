package com.zyke.message.domain.dashboard

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

enum class SourceKind { ALL, UPI, ACCOUNT, CARD }

/** Where money moved through: everything, UPI payments, one bank account, or one card. */
data class SourceFilter(val kind: SourceKind = SourceKind.ALL, val last4: String? = null) {
    val isActive: Boolean get() = kind != SourceKind.ALL
}

/** A source the person can choose, with how many payments went through it. */
data class SourceOption(val filter: SourceFilter, val count: Int)

object SourceFilters {
    private val UPI = Regex("(?i)\\bUPI\\b")
    private val ACCOUNT_IN_TEXT = Regex("(?i)(?:a/?c|acct?|account)\\s*(?:no\\.?)?\\s*[:\\-]?\\s*[xX*•]*(\\d{4})\\b")

    fun isUpi(message: ClassifiedMessage): Boolean = UPI.containsMatchIn(message.message.body)

    /** The last four digits of the account or card a payment went through; null when the message does not say. */
    fun last4Of(message: ClassifiedMessage, tx: MessageContent.Transaction): String? =
        tx.accountNumber.filter { it.isDigit() }.takeLast(4).takeIf { it.length == 4 }
            ?: ACCOUNT_IN_TEXT.find(message.message.body)?.groupValues?.get(1)

    private fun matches(message: ClassifiedMessage, filter: SourceFilter): Boolean {
        val tx = message.content as? MessageContent.Transaction ?: return false
        return when (filter.kind) {
            SourceKind.ALL -> true
            SourceKind.UPI -> isUpi(message)
            SourceKind.ACCOUNT -> !tx.isCard && last4Of(message, tx) == filter.last4
            SourceKind.CARD -> tx.isCard && last4Of(message, tx) == filter.last4
        }
    }

    /** The messages that went through [filter]; everything when no source is chosen. */
    fun apply(messages: List<ClassifiedMessage>, filter: SourceFilter): List<ClassifiedMessage> =
        if (!filter.isActive) messages else messages.filter { matches(it, filter) }

    /** The sources that have payments in them, busiest first within each kind. */
    fun options(messages: List<ClassifiedMessage>): List<SourceOption> {
        val transactions = messages.mapNotNull { m -> (m.content as? MessageContent.Transaction)?.let { m to it } }
        val upi = transactions.count { isUpi(it.first) }
        val accounts = transactions.filter { !it.second.isCard }.mapNotNull { (m, tx) -> last4Of(m, tx) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .map { SourceOption(SourceFilter(SourceKind.ACCOUNT, it.key), it.value) }
        val cards = transactions.filter { it.second.isCard }.mapNotNull { (m, tx) -> last4Of(m, tx) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .map { SourceOption(SourceFilter(SourceKind.CARD, it.key), it.value) }
        return listOfNotNull(upi.takeIf { it > 0 }?.let { SourceOption(SourceFilter(SourceKind.UPI), it) }) + accounts + cards
    }
}
