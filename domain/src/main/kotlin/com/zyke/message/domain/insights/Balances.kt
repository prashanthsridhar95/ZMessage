package com.zyke.message.domain.insights

import com.zyke.message.domain.util.toBigDecimalOrNull
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
    /**
     * "Avl Bal Rs 1,234.56", "Bal: INR -500.00", "Avl Bal Rs.1,234.56CR", "Balance in A/c XX1805 is Rs 99 Dr". Groups: 1 a sign before the
     * currency, 2 a sign after it, 3 the amount, 4 Cr or Dr after it (Dr means overdrawn).
     */
    private val BALANCE = Regex(
        "(?i)(?:avl\\.?\\s*bal(?:ance)?|available\\s+bal(?:ance)?|a/?c\\s*bal(?:ance)?|bal(?:ance)?)\\.?" +
            "(?:\\s+(?:in|of)\\s+(?:your\\s+|the\\s+)?(?:a/?c|acct?|account)\\s*(?:no\\.?)?\\s*[xX*\u2022]*\\d*)?" +
            "\\s*(?:is|:|=|-(?=\\s))?\\s*([-\\u2212])?\\s*(?:INR\\.?|Rs\\.?|\u20b9)\\s*([-\\u2212])?\\s*([\\d,]+(?:\\.\\d+)?)(?:\\s*(Cr|Dr)\\b)?"
    )
    /** The phrase a bank uses for the balance it means to state, whether or not the amount after it can be read. */
    private val BALANCE_PHRASE = Regex("(?i)\\b(?:avl|avbl|avail(?:able)?)\\.?\\s*bal")
    private val NOTICE_BALANCE = Regex("(?i)\\b[A-Z]{0,4}[xX*\u2022]*\\d*?(\\d{4})\\s+is\\s+(?:INR\\.?|Rs\\.?|\u20b9)\\s*([\\d,]+(?:\\.\\d+)?)")
    private val PF_BALANCE = Regex("(?i)\\bagainst\\s+[A-Z0-9*\u2022]*?(\\d{4})\\s+is\\s+(?:INR\\.?|Rs\\.?|\u20b9)\\s*([\\d,]+(?:\\.\\d+)?)")
    private val STATED_BALANCE = Regex("(?i)\\bbal(?:ance)?\\b.{0,60}?\\b(?:is|of|:|=|-(?=\\s))\\s*([-\\u2212])?\\s*(?:INR\\.?|Rs\\.?|\u20b9)\\s*([-\\u2212])?\\s*([\\d,]+(?:\\.\\d+)?)(?:\\s*(Cr|Dr)\\b)?")
    private val NOT_A_BALANCE = Regex("(?i)\\b(?:credit limit|avl\\.? limit|available limit|available credit|avl\\.? credit|outstanding|total due|min(?:imum)? due)\\b")
    private val ACCOUNT_IN_TEXT = Regex("(?i)(?:a/?c|acct?|account)\\s*(?:no\\.?)?\\s*[:\\-]?\\s*[xX*•]*\\s*(\\d{4})\\b")

    /** The amount a [BALANCE] or [STATED_BALANCE] match states: a minus sign before or after the currency, or a Dr suffix, makes it negative. */
    private fun signed(match: MatchResult): BigDecimal? {
        val g = match.groupValues
        val amount = g[3].replace(",", "").trimEnd('.').toBigDecimalOrNull() ?: return null
        val negative = g[1].isNotEmpty() || g[2].isNotEmpty() || g[4].equals("Dr", ignoreCase = true)
        return if (negative) amount.negate() else amount
    }

    /** What a message body states about balances; worked out once per body, so a rebuild does not read every body again. */
    private class Reading(raw: String) {
        val body: String = com.zyke.message.domain.util.BodyMemo.bounded(raw)
        val notABalance = NOT_A_BALANCE.containsMatchIn(body)
        val balanceMatches = BALANCE.containsMatchIn(body)
        /** The message says "Avl Bal" but not in a way the amount can be read from. */
        val unreadBalance = !balanceMatches && BALANCE_PHRASE.containsMatchIn(body)
        val balanceAmount: BigDecimal? by lazy { BALANCE.find(body)?.let { signed(it) } }
        val accountInText: String by lazy { ACCOUNT_IN_TEXT.find(body)?.groupValues?.get(1).orEmpty() }
    }

    /** The same, for a plain message that merely states a balance (a non-breaking space counts as a space). */
    private class Stated(raw: String) {
        val notABalance: Boolean
        val amount: BigDecimal?
        val account: String?

        init {
            val body = com.zyke.message.domain.util.BodyMemo.bounded(raw).replace('\u00a0', ' ')
            notABalance = NOT_A_BALANCE.containsMatchIn(body)
            if (notABalance) {
                amount = null
                account = null
            } else {
                amount = STATED_BALANCE.find(body)?.let { signed(it) }
                account = if (amount == null) null else ACCOUNT_IN_TEXT.find(body)?.groupValues?.get(1)
            }
        }
    }

    private class Notice(raw: String, pattern: Regex) {
        val lines: List<Pair<String, BigDecimal>> = pattern.findAll(com.zyke.message.domain.util.BodyMemo.bounded(raw).replace('\u00a0', ' ')).mapNotNull { match ->
            val amount = match.groupValues[2].replace(",", "").trimEnd('.').toBigDecimalOrNull() ?: return@mapNotNull null
            match.groupValues[1] to amount
        }.toList()
    }

    /** KVB: "INR 1,24,356.78 is the Balance in a/c ****4521 as of 14-AUG-2026 10:32:15". Groups: amount, last digits, date, time. */
    private val AMOUNT_FIRST_BALANCE = Regex(
        "(?i)(?:INR\\.?|Rs\\.?|\u20b9)\\s*([\\d,]+(?:\\.\\d+)?)\\s+is the (?:available |current |ledger )?balance in (?:your )?a/?c\\s*(?:no\\.?)?\\s*[xX*\u2022]*(\\d{4})\\b" +
            "(?:\\s+as (?:of|on)\\s+(\\d{1,2}-[A-Za-z]{3}-\\d{2,4})(?:\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?))?)?"
    )
    private val STAMP_DATE = java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("d-MMM-").appendValueReduced(java.time.temporal.ChronoField.YEAR, 2, 4, 2000).toFormatter(java.util.Locale.ENGLISH)

    private class StatedAt(raw: String) {
        val found: List<List<String>> = AMOUNT_FIRST_BALANCE.findAll(com.zyke.message.domain.util.BodyMemo.bounded(raw).replace('\u00a0', ' '))
            .map { it.groupValues.drop(1) }.toList()
    }

    private val statedAtMemo = com.zyke.message.domain.util.BodyMemo { body -> StatedAt(body) }

    private val readingsMemo = com.zyke.message.domain.util.BodyMemo { body -> Reading(body) }
    private val statedMemo = com.zyke.message.domain.util.BodyMemo { body -> Stated(body) }
    private val noticeMemo = com.zyke.message.domain.util.BodyMemo { body -> Notice(body, NOTICE_BALANCE) }
    private val pfMemo = com.zyke.message.domain.util.BodyMemo { body -> Notice(body, PF_BALANCE) }

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
            val read = readingsMemo.of(message.message)
            if (read.notABalance) return@mapNotNull null
            val amount = read.balanceAmount ?: return@mapNotNull null
            val account = tx.accountNumber.filter { it.isDigit() }.takeLast(4).ifEmpty { read.accountInText }
            if (account.isEmpty()) return@mapNotNull null
            AccountBalance(
                account, tx.bankName.displayString, amount,
                Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), message.id, message.message.date
            )
        }
        return all + messages.flatMap { noticeBalances(it, zone) } + messages.mapNotNull { statedBalance(it, zone) }
    }

    /** What moved in an account after a stated balance: the net, and how many credits and debits made it. */
    data class Movement(val net: BigDecimal, val credits: Int, val debits: Int) {
        val count: Int get() = credits + debits
    }

    /**
     * What came in and went out of [reading]'s account after the message that stated it, from messages that did not
     * state a balance themselves. Added to the stated balance this is the best guess at what the account holds now.
     * Nothing dated before the reading is touched, so an older credit (a salary, however it is filed) never moves it.
     */
    fun movedSince(messages: List<ClassifiedMessage>, reading: AccountBalance): Pair<BigDecimal, Int> =
        movement(messages, reading).let { it.net to it.count }

    fun movement(messages: List<ClassifiedMessage>, reading: AccountBalance): Movement {
        var net = BigDecimal.ZERO
        var credits = 0
        var debits = 0
        messages.forEach { message ->
            if (message.message.date <= reading.at) return@forEach
            val tx = message.content as? MessageContent.Transaction ?: return@forEach
            if (tx.isCard || tx.isCardPayment) return@forEach
            val read = readingsMemo.of(message.message)
            // A message that states its own balance would be a newer reading, never an adjustment.
            if (read.balanceMatches && !read.notABalance) return@forEach
            // One that says "Avl Bal" in a way that cannot be read leaves the account's true figure unknown: guessing from it would drift.
            if (read.unreadBalance && !read.notABalance) return@forEach
            val account = tx.accountNumber.filter { it.isDigit() }.takeLast(4).ifEmpty { read.accountInText }
            if (account != reading.account) return@forEach
            val amount = tx.amount.toBigDecimalOrNull() ?: return@forEach
            if (tx.isReceived) { net += amount; credits++ } else { net -= amount; debits++ }
        }
        return Movement(net, credits, debits)
    }

    /** A plain message that just states what an account holds ("balance in your A/c XX1234 is Rs. 5,000"), not a payment. */
    private fun statedBalance(message: ClassifiedMessage, zone: ZoneId): AccountBalance? {
        if (message.content != MessageContent.Generic || message.isPromotional) return null
        val stated = statedMemo.of(message.message)
        val amount = stated.amount ?: return null
        val account = stated.account ?: return null
        return AccountBalance(account, "", amount, Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), message.id, message.message.date)
    }

    /** The balances a bank's "available balance for a/c no(s) X is INR 1, Y is INR 2" message states, one per account. */
    fun noticeBalances(message: ClassifiedMessage, zone: ZoneId): List<AccountBalance> {
        val notice = message.content as? MessageContent.Notice ?: return emptyList()
        val found = when (notice.kind) {
            NoticeKind.BALANCE_UPDATE -> noticeMemo.of(message.message)
            // A passbook says "your passbook balance against <member id> is Rs. X"; the id's last digits name the fund.
            NoticeKind.PF_UPDATE -> pfMemo.of(message.message)
            else -> return emptyList()
        }
        val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
        val bank = com.zyke.message.domain.accounts.SenderBank.of(message.message.sender)?.displayString.orEmpty()
        val withStamp = if (notice.kind != NoticeKind.BALANCE_UPDATE) emptyList() else statedAtMemo.of(message.message).found.mapNotNull { g ->
            val amount = g[0].replace(",", "").trimEnd('.').toBigDecimalOrNull() ?: return@mapNotNull null
            // The balance is as of the time the bank states, which can be earlier than the message arrived.
            val day = runCatching { java.time.LocalDate.parse(g[2], STAMP_DATE) }.getOrNull()
            val time = if (g[3].isEmpty()) null else LifeTimes.parse(g[3])
            val at = if (day == null) message.message.date else day.atTime(time ?: java.time.LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
            AccountBalance(g[1], bank, amount, day ?: date, message.id, at)
        }
        return withStamp + found.lines.map { (account, amount) -> AccountBalance(account, bank, amount, date, message.id, message.message.date) }
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

private object LifeTimes {
    fun parse(raw: String): java.time.LocalTime? = runCatching {
        val parts = raw.split(':').map { it.toInt() }
        java.time.LocalTime.of(parts[0], parts[1], parts.getOrElse(2) { 0 })
    }.getOrNull()
}
