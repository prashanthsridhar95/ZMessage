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
    val userCard: UserCard?
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
        cycle: CycleRule = CycleRule.CALENDAR
    ): AccountsOverview {
        val today = LocalDate.ofInstant(now, zone)
        val range = cycle.containing(today)
        val balances = BalanceReader.latest(messages, zone).associateBy { it.account }

        val hits = messages.mapNotNull { message ->
            when (val c = message.content) {
                is MessageContent.Transaction -> hit(message, c.accountNumber, c.isCard, c.bankName)
                is MessageContent.ScheduledPayment -> hit(message, c.accountNumber, c.isCard, c.bankName)
                is MessageContent.CardBill -> hit(message, c.accountNumber, true, c.bankName)
                else -> null
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

            val balance: AccountBalance? = if (kind == AccountKind.BANK_ACCOUNT) balances[last4] else null
            AccountSummary(
                key = "${kind.name.lowercase()}-$last4",
                kind = kind,
                bankName = bank,
                last4 = last4,
                title = card?.name?.takeIf { it.isNotBlank() } ?: "${bank.displayString.ifEmpty { "Account" }} ${kind.label.lowercase()}",
                balance = balance?.amount,
                balanceAsOf = balance?.asOf,
                spent = spent,
                received = received,
                activityCount = count,
                lastActivity = newest.message.message.date,
                dueAmount = bill?.amount?.toBigDecimalOrNull(),
                dueDate = bill?.dueDate,
                minimumDue = bill?.minimumDue?.toBigDecimalOrNull(),
                paymentLink = bill?.paymentLink,
                sampleMessageId = newest.message.id,
                userCard = card
            )
        }.sortedWith(
            // Accounts first, then cards; the busiest first within each.
            compareBy<AccountSummary> { it.isCard }.thenByDescending { it.lastActivity }
        )

        val known = accounts.mapNotNull { it.balance }
        return AccountsOverview(accounts, known.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add), known.size)
    }

    private fun hit(message: ClassifiedMessage, accountNumber: String, isCard: Boolean, bank: BankName): Hit? {
        val digits = accountNumber.filter { it.isDigit() }.takeLast(LAST_DIGITS)
        return if (digits.length == LAST_DIGITS) Hit(message, digits, isCard, bank) else null
    }

    private const val LAST_DIGITS = 4
}
