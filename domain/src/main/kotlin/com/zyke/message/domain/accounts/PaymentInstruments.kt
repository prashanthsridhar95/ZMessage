package com.zyke.message.domain.accounts

import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

/** What a payment was funded from, as a row shows it: one small icon before the masked number. */
enum class PaymentInstrument(val spoken: String) {
    CREDIT_CARD("credit card"),
    DEBIT_CARD("debit card"),
    BANK_ACCOUNT("bank account"),
    /** A prepaid wallet or an on-device balance (UPI Lite). */
    WALLET("wallet"),
    FASTAG("FASTag"),
    /** The message does not say; no icon. */
    UNKNOWN("")
}

/**
 * Works out the [PaymentInstrument] of a message. The account the app already matched for the card or account number
 * (Accounts: its kind, the person's own choice included) wins over the words of the text, which are the fallback.
 * Wallet wording (UPI Lite, a wallet balance) and a FASTag message name their own instrument and win over the account
 * that happens to share the digits. Nothing here is stored: the result is a pure function of the message and the accounts.
 */
object PaymentInstruments {

    /** The accounts the person has, looked up by what a message states: the last four digits, whether it is a card message, the bank. */
    class Lookup private constructor(private val byIdentity: Map<Pair<String, Boolean>, List<AccountSummary>>) {
        /** The kind of account these digits are, or null when none matches or the match is ambiguous (two banks, two kinds). */
        fun kindOf(last4: String, isCard: Boolean, bank: BankName): AccountKind? {
            if (last4.isEmpty()) return null
            val candidates = byIdentity[last4 to isCard].orEmpty()
            if (candidates.isEmpty()) return null
            val sameBank = candidates.filter { it.bankName == bank || it.detectedBank == bank }
            val pool = if (sameBank.isNotEmpty()) sameBank else candidates
            return pool.map { it.kind }.distinct().singleOrNull()
        }

        companion object {
            val EMPTY = Lookup(emptyMap())
            fun of(accounts: List<AccountSummary>): Lookup =
                if (accounts.isEmpty()) EMPTY else Lookup(accounts.groupBy { it.last4 to it.messagesAreCard })
        }
    }

    private val DEBIT_WORDING = Regex("(?i)(?:\\bdebit\\s*card|\\bdr\\.?\\s*card|\\batm\\s*card|\\bd/c\\b|\\bDC\\b)")
    private val CREDIT_WORDING = Regex("(?i)(?:\\bcredit\\s*card|\\bcr\\.?\\s*card|\\bCC\\b)")
    private val WALLET_WORDING = Regex("(?i)\\b(?:UPI\\s*Lite|wallet|pay\\s*balance)\\b")
    private val ACCOUNT_WORDING = Regex("(?i)(?:\\ba/c\\b|\\bacct?\\b|\\baccount\\b|\\bupi\\b|\\bimps\\b|\\bneft\\b|\\brtgs\\b|net\\s*-?banking|\\batm\\b|\\bnach\\b|\\becs\\b|\\bcheque\\b|\\bchq\\b)")

    fun forKind(kind: AccountKind): PaymentInstrument = when (kind) {
        AccountKind.CREDIT_CARD -> PaymentInstrument.CREDIT_CARD
        AccountKind.DEBIT_CARD -> PaymentInstrument.DEBIT_CARD
        AccountKind.BANK_ACCOUNT -> PaymentInstrument.BANK_ACCOUNT
    }

    /** Only transactions and FASTag messages have an instrument; everything else is [PaymentInstrument.UNKNOWN]. */
    fun of(message: ClassifiedMessage, accounts: Lookup = Lookup.EMPTY): PaymentInstrument {
        val c = message.content
        if (c is MessageContent.FastTag) return PaymentInstrument.FASTAG
        if (c !is MessageContent.Transaction) return PaymentInstrument.UNKNOWN
        val body = message.message.body
        val payee = c.payee
        val last4 = c.accountNumber
        val isCard = c.isCard
        val bank = c.bankName
        // Topping up a wallet or UPI Lite from the bank: the payee is the wallet, the money left the account.
        val walletIsPayee = !c.isReceived && WALLET_WORDING.containsMatchIn(payee)
        val rest = if (payee.isEmpty()) body else body.replace(payee, " ", ignoreCase = true)
        if (!isCard && !walletIsPayee && WALLET_WORDING.containsMatchIn(rest)) return PaymentInstrument.WALLET
        val kind = accounts.kindOf(last4, isCard, bank)
        if (kind != null) return forKind(kind)
        return fromWording(body, isCard, last4.isNotEmpty())
    }

    /** The words alone: card kind, else a bank account when the text talks of one, else nothing to show. */
    fun fromWording(body: String, isCard: Boolean, hasNumber: Boolean): PaymentInstrument = when {
        isCard ->
            if (DEBIT_WORDING.containsMatchIn(body) && !CREDIT_WORDING.containsMatchIn(body)) PaymentInstrument.DEBIT_CARD
            else PaymentInstrument.CREDIT_CARD
        hasNumber || ACCOUNT_WORDING.containsMatchIn(body) -> PaymentInstrument.BANK_ACCOUNT
        else -> PaymentInstrument.UNKNOWN
    }
}
