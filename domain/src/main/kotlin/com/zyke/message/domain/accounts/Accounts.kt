package com.zyke.message.domain.accounts

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.insights.AccountBalance
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserCard
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class AccountKind(val label: String) {
    BANK_ACCOUNT("Bank account"),
    CREDIT_CARD("Credit card"),
    DEBIT_CARD("Debit card")
}

/** One bank account or card, put together from the messages that mention its last four digits. */
data class AccountSummary(
    /** Stable: the kind and the last four digits. */
    val key: String,
    val kind: AccountKind,
    val bankName: BankName,
    val last4: String,
    /** What to call it: the person's own name for it, else the bank and kind. */
    val title: String,
    /** What the account held at its latest message that said so; null for cards and when no message does. */
    val balance: BigDecimal?,
    val balanceAsOf: LocalDate?,
    /** Spent and received through it in the current pay cycle. */
    val spent: BigDecimal,
    val received: BigDecimal,
    val activityCount: Int,
    val lastActivity: Long,
    /** A card's latest bill. */
    val dueAmount: BigDecimal?,
    val dueDate: LocalDate?,
    val minimumDue: BigDecimal?,
    val paymentLink: String?,
    /** A message that carries this account's logo and opens its sheet. */
    val sampleMessageId: Long,
    val userCard: UserCard?,
    /** The statement day the card's bills point to, when the person has not set one. */
    val suggestedStatementDay: Int? = null,
    /** The person marked this as a provident fund; it is shown under Savings and kept out of the bank total. */
    val isProvidentFund: Boolean = false,
    /** The member id's last four digits came from an EPF passbook message, so this is a provident fund without being told. */
    val fromPassbook: Boolean = false,
    /** The message the balance was read from, so the person can check it. */
    val balanceMessageId: Long? = null,
    /** What came in and went out since the stated balance, already included in [balance]; null when nothing did. */
    val balanceAdjustment: BigDecimal? = null,
    /** The day the card's last statement was made; spending after it is not billed yet. */
    val statementOn: LocalDate? = null,
    /** What has been spent on the card since [statementOn], not yet on a bill. */
    val unbilled: BigDecimal? = null,
    /** The day the latest bill arrived. */
    val billedOn: LocalDate? = null,
    /** Payments credited to the card since that bill. */
    val paidSinceBill: BigDecimal? = null
) {
    val isCard: Boolean get() = kind != AccountKind.BANK_ACCOUNT
}

/** Everything the accounts screen shows. */
data class AccountsOverview(val accounts: List<AccountSummary>, val totalBalance: BigDecimal?, val countedBalances: Int)

object Accounts {
    private class Hit(val message: ClassifiedMessage, val last4: String, val isCard: Boolean, val bank: BankName)

    fun build(
        messages: List<ClassifiedMessage>,
        userCards: List<UserCard>,
        now: Instant,
        zone: ZoneId,
        cycle: CycleRule = CycleRule.CALENDAR,
        hidden: Set<String> = emptySet(),
        providentFunds: Set<String> = emptySet()
    ): AccountsOverview {
        val today = LocalDate.ofInstant(now, zone)
        val range = cycle.containing(today)
        val balances = BalanceReader.latest(messages, zone).associateBy { it.account }
        // The stated balance plus whatever has moved since it was stated, so a balance is not stale between messages.
        val moved = balances.mapValues { (_, reading) -> BalanceReader.movedSince(messages, reading) }
        fun current(last4: String): AccountBalance? = balances[last4]?.let { reading ->
            val (net, _) = moved.getValue(last4)
            if (net.signum() == 0) reading else reading.copy(amount = reading.amount + net)
        }
        // What the newest EPF passbook message says the fund holds, for a fund account that states no balance of its own.
        val passbook = messages.filter { isPassbook(it) }.flatMap { BalanceReader.noticeBalances(it, zone) }
            .maxWithOrNull(compareBy({ it.asOf }, { it.messageId }))

        val hits = messages.flatMap { message ->
            when (val c = message.content) {
                is MessageContent.Transaction -> listOfNotNull(hit(message, c.accountNumber, c.isCard, c.bankName))
                is MessageContent.ScheduledPayment -> listOfNotNull(hit(message, c.accountNumber, c.isCard, c.bankName))
                is MessageContent.CardBill -> listOfNotNull(hit(message, c.accountNumber, true, c.bankName))
                // A balance message names accounts without any payment; that is enough for them to be listed.
                is MessageContent.Notice -> BalanceReader.noticeBalances(message, zone).mapNotNull {
                    val bank = if (message.message.body.contains("Federal", ignoreCase = true)) BankName.FEDERAL_BANK else BankName.OTHERS
                    hit(message, it.account, false, bank)
                }
                else -> emptyList()
            }
        }

        val accounts = hits.groupBy { it.last4 to it.isCard }.map { (identity, rows) ->
            val (last4, isCard) = identity
            val newest = rows.maxBy { it.message.message.date }
            val bill = rows.filter { it.message.content is MessageContent.CardBill }.maxByOrNull { it.message.message.date }
                ?.let { it.message.content as MessageContent.CardBill }
            val card = userCards.firstOrNull { it.last4 == last4 && (it.type != CardType.ACCOUNT) == isCard }
            val kind = when {
                card?.type == CardType.CREDIT || bill != null -> AccountKind.CREDIT_CARD
                card?.type == CardType.DEBIT -> AccountKind.DEBIT_CARD
                isCard -> AccountKind.CREDIT_CARD
                else -> AccountKind.BANK_ACCOUNT
            }
            val bank = rows.map { it.bank }.firstOrNull { it != BankName.OTHERS } ?: newest.bank

            var spent = BigDecimal.ZERO
            var received = BigDecimal.ZERO
            var count = 0
            rows.forEach { row ->
                val tx = row.message.content as? MessageContent.Transaction ?: return@forEach
                val amount = tx.amount.toBigDecimalOrNull() ?: return@forEach
                val date = Instant.ofEpochMilli(row.message.message.date).atZone(zone).toLocalDate()
                if (date !in range) return@forEach
                count++
                if (tx.isReceived) received += amount else spent += amount
            }

            // Where the card's cycle stands: the statement day (set, or read from the bills), else the day the last bill came.
            val billDates = rows.filter { it.message.content is MessageContent.CardBill }
                .map { Instant.ofEpochMilli(it.message.message.date).atZone(zone).toLocalDate() }
            val suggested = if (kind == AccountKind.CREDIT_CARD && card?.statementDay == null) StatementDays.suggest(billDates) else null
            val billedOn = billDates.maxOrNull()
            val statementOn = if (kind != AccountKind.CREDIT_CARD) null else (card?.statementDay ?: suggested)?.let { lastStatement(today, it) } ?: billedOn
            var unbilled: BigDecimal? = null
            var paidSinceBill: BigDecimal? = null
            if (kind == AccountKind.CREDIT_CARD) {
                unbilled = statementOn?.let { since ->
                    rows.fold(BigDecimal.ZERO) { sum, row ->
                        val tx = row.message.content as? MessageContent.Transaction ?: return@fold sum
                        val amount = tx.amount.toBigDecimalOrNull() ?: return@fold sum
                        val date = Instant.ofEpochMilli(row.message.message.date).atZone(zone).toLocalDate()
                        if (tx.isReceived || tx.isCardPayment || !date.isAfter(since) || date.isAfter(today)) sum else sum + amount
                    }
                }
                paidSinceBill = billedOn?.let { from ->
                    rows.fold(BigDecimal.ZERO) { sum, row ->
                        val tx = row.message.content as? MessageContent.Transaction ?: return@fold sum
                        val amount = tx.amount.toBigDecimalOrNull() ?: return@fold sum
                        val date = Instant.ofEpochMilli(row.message.message.date).atZone(zone).toLocalDate()
                        if (tx.isReceived && !date.isBefore(from)) sum + amount else sum
                    }
                }?.takeIf { it.signum() > 0 }
            }

            val markedFund = "${kind.name.lowercase()}-$last4" in providentFunds
            val balance: AccountBalance? = if (kind == AccountKind.BANK_ACCOUNT) {
                current(last4) ?: passbook.takeIf { markedFund }
            } else null
            AccountSummary(
                key = "${kind.name.lowercase()}-$last4",
                kind = kind,
                bankName = bank,
                last4 = last4,
                title = card?.name?.takeIf { it.isNotBlank() } ?: "${bank.displayString.ifEmpty { "Account" }} ${kind.label.lowercase()}",
                balance = balance?.amount,
                balanceAsOf = balance?.asOf,
                balanceMessageId = balance?.messageId,
                balanceAdjustment = moved[last4]?.first?.takeIf { it.signum() != 0 && balance != null && balances[last4] != null && kind == AccountKind.BANK_ACCOUNT && !markedFund },
                spent = spent,
                received = received,
                activityCount = count,
                lastActivity = newest.message.message.date,
                dueAmount = bill?.amount?.toBigDecimalOrNull(),
                dueDate = bill?.dueDate,
                minimumDue = bill?.minimumDue?.toBigDecimalOrNull(),
                paymentLink = bill?.paymentLink,
                sampleMessageId = newest.message.id,
                userCard = card,
                isProvidentFund = "${kind.name.lowercase()}-$last4" in providentFunds ||
                    (kind == AccountKind.BANK_ACCOUNT && rows.all { isPassbook(it.message) }),
                fromPassbook = rows.all { isPassbook(it.message) },
                suggestedStatementDay = suggested,
                statementOn = statementOn,
                unbilled = unbilled,
                billedOn = billedOn,
                paidSinceBill = paidSinceBill
            )
        }.let { fromMessages ->
            // An account or card the person added is listed even before a message mentions it.
            fromMessages + userCards.filter { card -> fromMessages.none { it.last4 == card.last4 && it.isCard == (card.type != CardType.ACCOUNT) } }.map { card ->
                val kind = when (card.type) {
                    CardType.CREDIT -> AccountKind.CREDIT_CARD
                    CardType.DEBIT -> AccountKind.DEBIT_CARD
                    CardType.ACCOUNT -> AccountKind.BANK_ACCOUNT
                }
                val balance = if (kind == AccountKind.BANK_ACCOUNT) current(card.last4) ?: passbook.takeIf { "${kind.name.lowercase()}-${card.last4}" in providentFunds } else null
                AccountSummary(
                    key = "${kind.name.lowercase()}-${card.last4}", kind = kind, bankName = BankName.OTHERS, last4 = card.last4,
                    title = card.name.ifBlank { card.bankName.ifBlank { kind.label } },
                    balance = balance?.amount, balanceAsOf = balance?.asOf, balanceMessageId = balance?.messageId,
                    spent = BigDecimal.ZERO, received = BigDecimal.ZERO, activityCount = 0, lastActivity = 0L,
                    dueAmount = null, dueDate = null, minimumDue = null, paymentLink = null, sampleMessageId = 0L, userCard = card,
                    isProvidentFund = "${kind.name.lowercase()}-${card.last4}" in providentFunds
                )
            }
        }.let { all -> if (all.any { it.isProvidentFund && !it.fromPassbook }) all.filter { !it.fromPassbook } else all }
         .filter { it.key !in hidden }.sortedWith(
            // Accounts first, then cards; the busiest first within each.
            compareBy<AccountSummary> { it.isCard }.thenByDescending { it.lastActivity }
        )

        val known = accounts.filter { !it.isProvidentFund }.mapNotNull { it.balance }
        return AccountsOverview(accounts, known.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add), known.size)
    }

    /** The latest date up to [today] that falls on [day] of the month (a short month's last day stands in for 31). */
    private fun lastStatement(today: LocalDate, day: Int): LocalDate {
        val thisMonth = today.withDayOfMonth(minOf(day, today.lengthOfMonth()))
        if (!thisMonth.isAfter(today)) return thisMonth
        val previous = today.minusMonths(1)
        return previous.withDayOfMonth(minOf(day, previous.lengthOfMonth()))
    }

    private fun isPassbook(message: ClassifiedMessage) = (message.content as? MessageContent.Notice)?.kind == com.zyke.message.domain.model.NoticeKind.PF_UPDATE

    private fun hit(message: ClassifiedMessage, accountNumber: String, isCard: Boolean, bank: BankName): Hit? {
        val digits = accountNumber.filter { it.isDigit() }.takeLast(LAST_DIGITS)
        return if (digits.length == LAST_DIGITS) Hit(message, digits, isCard, bank) else null
    }

    private const val LAST_DIGITS = 4
}
