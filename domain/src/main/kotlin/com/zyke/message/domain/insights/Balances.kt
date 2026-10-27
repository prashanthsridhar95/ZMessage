package com.zyke.message.domain.insights

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What an account held at the time of its latest message that said so. */
data class AccountBalance(
    /** Last digits of the account. */
    val account: String,
    val bank: String,
    val amount: BigDecimal,
    val asOf: LocalDate,
    val messageId: Long
)

/** Reads the "Avl Bal" that banks put at the end of debit and credit messages. */
object BalanceReader {
    private val BALANCE = Regex(
        "(?i)(?:avl\\.?\\s*bal(?:ance)?|available\\s+bal(?:ance)?|a/?c\\s*bal(?:ance)?|bal(?:ance)?)\\s*(?:is|:|-|=)?\\s*(?:INR\\.?|Rs\\.?|₹)\\s*([\\d,]+(?:\\.\\d+)?)"
    )
    private val NOT_A_BALANCE = Regex("(?i)\\b(?:credit limit|avl\\.? limit|available limit|total due|min(?:imum)? due)\\b")
    private val ACCOUNT_IN_TEXT = Regex("(?i)(?:a/?c|acct?|account)\\s*(?:no\\.?)?\\s*[:\\-]?\\s*[xX*•]*\\s*(\\d{4})\\b")

    /** The newest balance seen for each account. */
    fun latest(messages: List<ClassifiedMessage>, zone: ZoneId): List<AccountBalance> =
        readings(messages, zone).groupBy { it.account }.map { (_, balances) -> balances.maxWith(compareBy({ it.asOf }, { it.messageId })) }

    /** Every balance an account's messages stated, oldest first, keyed by the account's last digits. */
    fun history(messages: List<ClassifiedMessage>, zone: ZoneId): Map<String, List<AccountBalance>> =
        readings(messages, zone).groupBy { it.account }.mapValues { (_, list) -> list.sortedWith(compareBy({ it.asOf }, { it.messageId })) }

    private fun readings(messages: List<ClassifiedMessage>, zone: ZoneId): List<AccountBalance> {
        val all = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            // A card's "available balance" is credit left, not money in an account.
            if (tx.isCard) return@mapNotNull null
            val body = message.message.body
            if (NOT_A_BALANCE.containsMatchIn(body)) return@mapNotNull null
            val amount = BALANCE.find(body)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull() ?: return@mapNotNull null
            val account = tx.accountNumber.filter { it.isDigit() }.takeLast(4).ifEmpty {
                ACCOUNT_IN_TEXT.find(body)?.groupValues?.get(1).orEmpty()
            }
            if (account.isEmpty()) return@mapNotNull null
            AccountBalance(
                account, tx.bankName.displayString, amount,
                Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), message.id
            )
        }
        return all
    }
}

/** Warns when an announced auto-debit looks bigger than what the account last said it held. */
object LowBalance {
    private const val LOOKAHEAD_DAYS = 3L

    fun warnings(
        messages: List<ClassifiedMessage>,
        balances: List<AccountBalance>,
        today: LocalDate
    ): List<Nudge> {
        val byAccount = balances.associateBy { it.account }
        return messages.mapNotNull { message ->
            val debit = message.content as? MessageContent.ScheduledPayment ?: return@mapNotNull null
            if (debit.kind != ScheduledKind.UPCOMING_DEBIT || debit.isCard) return@mapNotNull null
            val date = debit.date ?: return@mapNotNull null
            if (date.isBefore(today) || date.isAfter(today.plusDays(LOOKAHEAD_DAYS))) return@mapNotNull null
            val amount = debit.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            val balance = byAccount[debit.accountNumber.filter { it.isDigit() }.takeLast(4)] ?: return@mapNotNull null
            if (balance.amount >= amount) return@mapNotNull null

            val day = when (date) {
                today -> "today"
                today.plusDays(1) -> "tomorrow"
                else -> "on ${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}"
            }
            val payee = debit.payee.ifEmpty { "an auto-debit" }
            Nudge(
                NudgeKind.LOW_BALANCE,
                "${balance.bank.ifEmpty { "Account" }} •• ${balance.account} showed ${PaceNudges.money(balance.amount)} on " +
                    "${balance.asOf.dayOfMonth} ${balance.asOf.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}, " +
                    "but ${PaceNudges.money(amount)} for $payee is due $day.",
                message.id
            )
        }.distinctBy { it.text }
    }
}
