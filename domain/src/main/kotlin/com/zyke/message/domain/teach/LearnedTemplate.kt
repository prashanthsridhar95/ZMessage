package com.zyke.message.domain.teach

import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.BankTransaction
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal

/** Which capture groups of a [LearnedTemplate.pattern] hold what; counted from 1. */
data class TemplateGroups(val amount: Int, val payee: Int? = null, val account: Int? = null)

/**
 * A message format the person taught the app, for one sender family. [pattern] is a masked regex: the words around
 * the amount, payee and account are literal, every other number is generalised. No message text is kept, only this.
 */
data class LearnedTemplate(
    val id: String,
    val senderKey: String,
    val pattern: String,
    val groups: TemplateGroups,
    val isReceived: Boolean,
    val category: MessageCategory? = null
) {
    private val regex: Regex? by lazy { runCatching { Regex(pattern) }.getOrNull() }

    /** True when [message] comes from this template's sender family. */
    fun fits(sender: String): Boolean = SenderKey.of(sender) == senderKey

    /** What this template reads from [message], or null unless the sender family matches and the whole text matches. */
    fun apply(message: SmsMessage): BankTransaction? {
        if (!fits(message.sender)) return null
        val body = message.body.trim()
        if (body.length > TemplateLearner.MAX_BODY) return null
        val match = regex?.matchEntire(body) ?: return null
        val amount = match.groupValues.getOrNull(groups.amount)?.takeIf { TemplateLearner.isPositive(it) } ?: return null
        val payee = groups.payee?.let { match.groupValues.getOrNull(it) }?.collapse().orEmpty()
        if (payee.length > TemplateLearner.MAX_PAYEE) return null
        val account = groups.account?.let { match.groupValues.getOrNull(it) }.orEmpty().takeLast(ACCOUNT_DIGITS)
        return BankTransaction(
            bankName = BankName.OTHERS,
            accountNumber = account,
            payee = payee,
            amount = amount,
            type = if (isReceived) BankTransactionType.RECEIVED else BankTransactionType.SENT
        )
    }

    private fun String.collapse() = trim().replace(Regex("\\s+"), " ")

    private companion object {
        const val ACCOUNT_DIGITS = 4
    }
}

/** Where to find the taught values: indexes into [TemplateLearner.tokenize] of the message being taught. */
data class TeachPicks(
    val amount: Int,
    /** First and last token of the payee, inclusive. */
    val payee: IntRange? = null,
    val account: Int? = null,
    val isReceived: Boolean = false,
    val category: MessageCategory? = null
)

/** The first template, in the order taught, that reads [message]. */
object LearnedTemplates {
    fun firstMatch(templates: List<LearnedTemplate>, message: SmsMessage): Pair<LearnedTemplate, BankTransaction>? {
        for (template in templates) template.apply(message)?.let { return template to it }
        return null
    }

    fun isValidAmount(text: String): Boolean = TemplateLearner.isPositive(text)

    /** Used only to compare an amount that was read with the one that was picked. */
    internal fun decimal(text: String): BigDecimal? = text.replace(",", "").toBigDecimalOrNull()
}
