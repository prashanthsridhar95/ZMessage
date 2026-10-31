package com.zyke.message.domain.insights

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
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
    val messageId: Long,
    /** When the message that stated it arrived, in epoch millis. */
    val at: Long = 0L
)

/** Reads the "Avl Bal" that banks put at the end of debit and credit messages. */
object BalanceReader {
    private val BALANCE = Regex(
        "(?i)(?:avl\\.?\\s*bal(?:ance)?|available\\s+bal(?:ance)?|a/?c\\s*bal(?:ance)?|bal(?:ance)?)\\s*(?:is|:|-|=)?\\s*(?:INR\\.?|Rs\\.?|₹)\\s*([\\d,]+(?:\\.\\d+)?)"
    )
    private val NOTICE_BALANCE = Regex("(?i)\\b[A-Z]{0,4}[xX*\u2022]*\\d*?(\\d{4})\\s+is\\s+(?:INR\\.?|Rs\\.?|\u20b9)\\s*([\\d,]+(?:\\.\\d+)?)")
    private val PF_BALANCE = Regex("(?i)\\bagainst\\s+[A-Z0-9*\u2022]*?(\\d{4})\\s+is\\s+(?:INR\\.?|Rs\\.?|\u20b9)\\s*([\\d,]+(?:\\.\\d+)?)")
    private val STATED_BALANCE = Regex("(?i)\\bbal(?:ance)?\\b.{0,60}?\\b(?:is|of|:|-|=)\\s*(?:INR\\.?|Rs\\.?|\u20b9)\\s*([\\d,]+(?:\\.\\d+)?)")
    private val NOT_A_BALANCE = Regex("(?i)\\b(?:credit limit|avl\\.? limit|available limit|total due|min(?:imum)? due)\\b")
    private val ACCOUNT_IN_TEXT = Regex("(?i)(?:a/?c|acct?|account)\\s*(?:no\\.?)?\\s*[:\\-]?\\s*[xX*•]*\\s*(\\d{4})\\b")

    /** The newest balance seen for each account. */
    fun latest(messages: List<ClassifiedMessage>, zone: ZoneId): List<AccountBalance> =
        readings(messages, zone).groupBy { it.account }.map { (_, balances) -> balances.maxWith(compareBy({ it.asOf }, { it.messageId })) }

    /** Every balance an account's messages stated, oldest first, keyed by the account's last digits. */
    fun history(messages: List<ClassifiedMessage>, zone: ZoneId): Map<String, List<AccountBalance>> =
        readings(messages, zone).groupBy { it.account }.mapValues { (_, list) -> list.sortedWith(compareBy({ it.asOf }, { it.messageId })) }

    /** The balance one message states for its account, if it does. */
    fun statedIn(message: ClassifiedMessage, zone: ZoneId): AccountBalance? =
        readings(listOf(message), zone).maxByOrNull { it.asOf }

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
                Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), message.id, message.message.date
            )
        }
        return all + messages.flatMap { noticeBalances(it, zone) } + messages.mapNotNull { statedBalance(it, zone) }
    }

    /**
     * What came in and went out of [reading]'s account after the message that stated it, from messages that did not
     * state a balance themselves. Added to the stated balance this is the best guess at what the account holds now.
     * Nothing dated before the reading is touched, so an older credit (a salary, however it is filed) never moves it.
     */
    fun movedSince(messages: List<ClassifiedMessage>, reading: AccountBalance): Pair<BigDecimal, Int> {
        var net = BigDecimal.ZERO
        var count = 0
        messages.forEach { message ->
            if (message.message.date <= reading.at) return@forEach
            val tx = message.content as? MessageContent.Transaction ?: return@forEach
            if (tx.isCard || tx.isCardPayment) return@forEach
            val body = message.message.body
            // A message that states its own balance would be a newer reading, never an adjustment.
            if (BALANCE.containsMatchIn(body) && !NOT_A_BALANCE.containsMatchIn(body)) return@forEach
            val account = tx.accountNumber.filter { it.isDigit() }.takeLast(4).ifEmpty { ACCOUNT_IN_TEXT.find(body)?.groupValues?.get(1).orEmpty() }
            if (account != reading.account) return@forEach
            val amount = tx.amount.toBigDecimalOrNull() ?: return@forEach
            net += if (tx.isReceived) amount else amount.negate()
            count++
        }
        return net to count
    }

    /** A plain message that just states what an account holds ("balance in your A/c XX1234 is Rs. 5,000"), not a payment. */
    private fun statedBalance(message: ClassifiedMessage, zone: ZoneId): AccountBalance? {
        if (message.content != MessageContent.Generic || message.isPromotional) return null
        val body = message.message.body.replace('\u00a0', ' ')
        if (NOT_A_BALANCE.containsMatchIn(body)) return null
        val amount = STATED_BALANCE.find(body)?.groupValues?.get(1)?.replace(",", "")?.trimEnd('.')?.toBigDecimalOrNull() ?: return null
        val account = ACCOUNT_IN_TEXT.find(body)?.groupValues?.get(1) ?: return null
        return AccountBalance(account, "", amount, Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), message.id, message.message.date)
    }

    /** The balances a bank's "available balance for a/c no(s) X is INR 1, Y is INR 2" message states, one per account. */
    fun noticeBalances(message: ClassifiedMessage, zone: ZoneId): List<AccountBalance> {
        val notice = message.content as? MessageContent.Notice ?: return emptyList()
        val pattern = when (notice.kind) {
            NoticeKind.BALANCE_UPDATE -> NOTICE_BALANCE
            // A passbook says "your passbook balance against <member id> is Rs. X"; the id's last digits name the fund.
            NoticeKind.PF_UPDATE -> PF_BALANCE
            else -> return emptyList()
        }
        val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
        return pattern.findAll(message.message.body.replace('\u00a0', ' ')).mapNotNull { match ->
            val amount = match.groupValues[2].replace(",", "").trimEnd('.').toBigDecimalOrNull() ?: return@mapNotNull null
            AccountBalance(match.groupValues[1], "", amount, date, message.id, message.message.date)
        }.toList()
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
