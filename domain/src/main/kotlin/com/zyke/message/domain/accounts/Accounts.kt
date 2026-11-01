package com.zyke.message.domain.accounts

import com.zyke.message.domain.util.toBigDecimalOrNull
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.insights.AccountBalance
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.cards.CardCatalog
import com.zyke.message.domain.diag.DiagLog
import com.zyke.message.domain.home.BillPayments
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
    /**
     * Stable: the kind and the last four digits. Where two banks' accounts share those digits the busier one keeps this
     * key and the other adds its bank (kind-1805-icici), so what the person set against the old key still finds its account.
     */
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
    val paidSinceBill: BigDecimal? = null,
    /** The bank as it reads: the person's choice, else the bank's short name; free text when the bank is not a known one. */
    val bankLabel: String = bankName.displayString,
    /** What the messages say the bank and type are, whatever the person chose. */
    val detectedBank: BankName = bankName,
    val detectedKind: AccountKind = kind,
    /** What the person set for this account; null when they have not. */
    val override: AccountOverride? = null
) {
    val isCard: Boolean get() = kind != AccountKind.BANK_ACCOUNT

    /** Whether this account's payment messages are card messages, however the person typed it: what to look its activity up by. */
    val messagesAreCard: Boolean get() = detectedKind != AccountKind.BANK_ACCOUNT

    /** The latest bill has been paid in full (to within a rupee), so what is owed now is only what was spent after it. */
    val billPaid: Boolean
        get() = kind == AccountKind.CREDIT_CARD && dueAmount != null && paidSinceBill != null &&
            paidSinceBill >= dueAmount - BigDecimal.ONE
}

/** Everything the accounts screen shows. */
data class AccountsOverview(val accounts: List<AccountSummary>, val totalBalance: BigDecimal?, val countedBalances: Int)

object Accounts {
    /** [bank] is what the body's words suggest; [senderBank] is what the sender header says, which is the better evidence. */
    private class Hit(val message: ClassifiedMessage, val last4: String, val isCard: Boolean, val bank: BankName, val senderBank: BankName?)

    /** One account: the messages that are about it, and whether another bank's account shares its digits. */
    private class Group(val last4: String, val isCard: Boolean, val rows: List<Hit>, val keySuffix: String, val split: Boolean)

    /** The bank most of [rows] point to: the sender's word over the body's, the most common, the newest on a tie. */
    internal fun voteBank(rows: List<Pair<BankName?, Long>>): BankName? {
        val votes = rows.filter { it.first != null }
        if (votes.isEmpty()) return null
        return votes.groupBy { it.first!! }.maxWith(compareBy({ it.value.size }, { it.value.maxOf { v -> v.second } })).key
    }

    private fun bankOf(rows: List<Hit>): BankName {
        val bySender = voteBank(rows.map { it.senderBank to it.message.message.date })
        if (bySender != null) return bySender
        return voteBank(rows.map { (it.bank.takeIf { b -> b != BankName.OTHERS }) to it.message.message.date }) ?: BankName.OTHERS
    }

    /** Splits the messages for one kind and last four into one account per bank when their senders name more than one. */
    private fun split(last4: String, isCard: Boolean, rows: List<Hit>): List<Group> {
        val banks = rows.mapNotNull { it.senderBank }.distinct()
        if (banks.size < 2) return listOf(Group(last4, isCard, rows, "", false))
        val dominant = voteBank(rows.map { it.senderBank to it.message.message.date })!!
        return banks.map { bank ->
            val mine = rows.filter { it.senderBank == bank || (it.senderBank == null && bank == dominant) }
            Group(last4, isCard, mine, if (bank == dominant) "" else "-${bank.name.lowercase()}", true)
        }
    }

    fun build(
        messages: List<ClassifiedMessage>,
        userCards: List<UserCard>,
        now: Instant,
        zone: ZoneId,
        cycle: CycleRule = CycleRule.CALENDAR,
        hidden: Set<String> = emptySet(),
        providentFunds: Set<String> = emptySet(),
        /** What the person says an account really is, by account key; applied over what the messages suggest. */
        overrides: Map<String, AccountOverride> = emptyMap()
    ): AccountsOverview {
        val today = LocalDate.ofInstant(now, zone)
        val range = cycle.containing(today)
        val balances = BalanceReader.latest(messages, zone).associateBy { it.account }
        // The stated balance plus whatever has moved since it was stated, so a balance is not stale between messages.
        // A balance worked out for one account: what it last stated and what has moved since. When the messages that moved
        // it all went one way and the sum went below zero while the statement said otherwise, the statement stands: a
        // payment whose credit never reached the messages is the likelier fault, not an overdraft.
        class Worked(val balance: AccountBalance, val adjustment: BigDecimal?, val movement: BalanceReader.Movement, val dropped: Boolean)
        fun work(reading: AccountBalance, movement: BalanceReader.Movement): Worked {
            if (movement.net.signum() == 0) return Worked(reading, null, movement, false)
            val adjusted = reading.amount + movement.net
            if (adjusted.signum() < 0 && reading.amount.signum() >= 0) return Worked(reading, null, movement, true)
            return Worked(reading.copy(amount = adjusted), movement.net, movement, false)
        }
        fun current(last4: String, scope: List<ClassifiedMessage>? = null): Worked? {
            if (scope == null) return balances[last4]?.let { work(it, BalanceReader.movement(messages, it)) }
            val reading = BalanceReader.latest(scope, zone).filter { it.account == last4 }
                .maxWithOrNull(compareBy({ it.asOf }, { it.messageId })) ?: return null
            return work(reading, BalanceReader.movement(scope, reading))
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
                    val bank = if (message.message.body.contains("Federal", ignoreCase = true)) BankName.FEDERAL_BANK
                    else SenderBank.of(message.message.sender) ?: BankName.OTHERS
                    hit(message, it.account, false, bank)
                }
                else -> emptyList()
            }
        }

        val groups = hits.groupBy { it.last4 to it.isCard }.flatMap { (identity, rows) -> split(identity.first, identity.second, rows) }
        var splitAccounts = 0
        val accounts = groups.map { group ->
            val rows = group.rows
            val last4 = group.last4
            val isCard = group.isCard
            val newest = rows.maxBy { it.message.message.date }
            val bill = rows.filter { it.message.content is MessageContent.CardBill }.maxByOrNull { it.message.message.date }
                ?.let { it.message.content as MessageContent.CardBill }
            val detectedBank = bankOf(rows)
            val card = cardFor(userCards, last4, isCard, group.split, detectedBank)
            val detectedKind = when {
                card?.type == CardType.CREDIT || bill != null -> AccountKind.CREDIT_CARD
                card?.type?.isDebitLike == true -> AccountKind.DEBIT_CARD
                isCard -> AccountKind.CREDIT_CARD
                else -> AccountKind.BANK_ACCOUNT
            }
            val key = "${detectedKind.name.lowercase()}-$last4${group.keySuffix}"
            val picked = overrides[key]?.takeUnless { it.isEmpty }
            val kind = picked?.kind ?: detectedKind
            val (bank, bankLabel) = chosenBank(picked, card, detectedBank)
            if (group.split) splitAccounts++

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
                // A payment made from a bank account may never have reached the card's own messages: the bank's debit note counts too.
                val newestBill = rows.filter { it.message.content is MessageContent.CardBill }.maxByOrNull { it.message.message.date }?.message
                val viaMessages = newestBill?.let { BillPayments.find(it, it.content as MessageContent.CardBill, messages, zone) }?.amount
                if (viaMessages != null && (paidSinceBill == null || viaMessages > paidSinceBill!!)) paidSinceBill = viaMessages
            }

            val markedFund = key in providentFunds
            val worked = if (kind == AccountKind.BANK_ACCOUNT) current(last4, if (group.split) rows.map { it.message }.distinct() else null) else null
            val balance: AccountBalance? = if (kind == AccountKind.BANK_ACCOUNT) {
                worked?.balance ?: passbook.takeIf { markedFund }
            } else null
            if (worked != null) audit(key, worked.balance.amount, worked.movement, worked.dropped, worked.adjustment != null)
            AccountSummary(
                key = key,
                kind = kind,
                bankName = bank,
                last4 = last4,
                title = card?.name?.takeIf { it.isNotBlank() } ?: "${bankLabel.ifEmpty { "Account" }} ${kind.label.lowercase()}",
                balance = balance?.amount,
                balanceAsOf = balance?.asOf,
                balanceMessageId = balance?.messageId,
                balanceAdjustment = worked?.adjustment?.takeIf { balance != null && kind == AccountKind.BANK_ACCOUNT && !markedFund },
                spent = spent,
                received = received,
                activityCount = count,
                lastActivity = newest.message.message.date,
                dueAmount = bill?.amount?.toBigDecimalOrNull(),
                dueDate = bill?.dueDate,
                minimumDue = bill?.minimumDue?.toBigDecimalOrNull(),
                paymentLink = bill?.paymentLink,
                sampleMessageId = newest.message.id,
                userCard = card?.let { shown(it, picked) },
                bankLabel = bankLabel,
                detectedBank = detectedBank,
                detectedKind = detectedKind,
                override = picked,
                isProvidentFund = key in providentFunds ||
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
            fromMessages + userCards.filter { card -> fromMessages.none { it.userCard?.id == card.id } }.map { card ->
                val detectedKind = when (card.type) {
                    CardType.CREDIT -> AccountKind.CREDIT_CARD
                    CardType.DEBIT, CardType.PREPAID, CardType.FOREX -> AccountKind.DEBIT_CARD
                    CardType.ACCOUNT -> AccountKind.BANK_ACCOUNT
                }
                val key = "${detectedKind.name.lowercase()}-${card.last4}"
                val picked = overrides[key]?.takeUnless { it.isEmpty }
                val kind = picked?.kind ?: detectedKind
                val (bank, bankLabel) = chosenBank(picked, card, BankName.OTHERS)
                val balance = if (kind == AccountKind.BANK_ACCOUNT) current(card.last4)?.balance ?: passbook.takeIf { key in providentFunds } else null
                AccountSummary(
                    key = key, kind = kind, bankName = bank, last4 = card.last4,
                    title = card.name.ifBlank { card.bankName.ifBlank { kind.label } },
                    balance = balance?.amount, balanceAsOf = balance?.asOf, balanceMessageId = balance?.messageId,
                    spent = BigDecimal.ZERO, received = BigDecimal.ZERO, activityCount = 0, lastActivity = 0L,
                    dueAmount = null, dueDate = null, minimumDue = null, paymentLink = null, sampleMessageId = 0L, userCard = shown(card, picked),
                    isProvidentFund = key in providentFunds,
                    bankLabel = bankLabel, detectedBank = BankName.OTHERS, detectedKind = detectedKind, override = picked
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
        return if (digits.length == LAST_DIGITS) Hit(message, digits, isCard, bank, SenderBank.of(message.message.sender)) else null
    }

    /** A card the app made while naming or setting up a found account; its bank is a copy of what was detected, not a choice. */
    private fun isAutoNamed(card: UserCard) = card.id.startsWith("named-")

    /**
     * The bank to show: the person's pick for this account, else the bank on their card or account entry (Settings, Cards and
     * accounts), else what the messages say. A known bank gives its short name; any other is shown as typed.
     */
    private fun chosenBank(picked: AccountOverride?, card: UserCard?, detected: BankName): Pair<BankName, String> {
        val typed = picked?.bank?.trim().orEmpty().ifEmpty { card?.takeUnless { isAutoNamed(it) }?.bankName?.trim().orEmpty() }
        if (typed.isEmpty()) return detected to detected.displayString
        val known = CardCatalog.bank(typed)
        return if (known != null) known to known.displayString else BankName.OTHERS to typed
    }

    /** The person's card entry for these digits. Where two banks share them, the entry goes to the bank it names, else the busier account. */
    private fun cardFor(cards: List<UserCard>, last4: String, isCard: Boolean, split: Boolean, detected: BankName): UserCard? {
        val same = cards.filter { it.last4 == last4 && (it.type != CardType.ACCOUNT) == isCard }
        if (!split) return same.firstOrNull()
        return same.firstOrNull { CardCatalog.bank(it.bankName) == detected }
    }

    /** The card as it reads on this account: with the person's bank choice on its face. */
    private fun shown(card: UserCard, picked: AccountOverride?): UserCard =
        if (picked != null && picked.bank.isNotBlank() && card.bankName != picked.bank) card.copy(bankName = picked.bank) else card

    /** A line for the developer log when a balance came out below zero or an adjustment would have flipped it; counts and signs only. */
    private fun audit(key: String, balance: BigDecimal, movement: BalanceReader.Movement, dropped: Boolean, applied: Boolean) {
        if (balance.signum() >= 0 && !dropped) return
        DiagLog.d("accounts") {
            "balance below zero or adjustment refused: account=${DiagLog.key(key)} balanceSign=${balance.signum()} adjustmentApplied=$applied " +
                "refusedAsNegative=$dropped netSign=${movement.net.signum()} credits=${movement.credits} debits=${movement.debits}"
        }
    }

    private const val LAST_DIGITS = 4
}
