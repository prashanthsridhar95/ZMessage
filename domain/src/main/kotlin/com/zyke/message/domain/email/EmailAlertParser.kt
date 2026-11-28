package com.zyke.message.domain.email

import com.zyke.message.domain.classification.BankMessageParser
import com.zyke.message.domain.classification.GenericTransactionParser
import com.zyke.message.domain.classification.OtpParser
import com.zyke.message.domain.diagnostics.MessageRedactor
import com.zyke.message.domain.model.BankTransactionType

/** Why a mail from a listed address was not counted as a payment. None of these goes to "Messages not understood". */
enum class SkipReason { OTP, STATEMENT, MARKETING, NOT_A_PAYMENT }

/** What reading one email came to. */
sealed interface EmailRead {
    /** Worded like the bank's own alert and read in full. */
    data class Understood(val alert: EmailAlert) : EmailRead

    /** From a listed address and about money moving, but not in any wording this parser knows; kept as a masked snippet only. */
    data class NotUnderstood(val alert: EmailAlert) : EmailRead

    data class Skipped(val reason: SkipReason) : EmailRead
}

/**
 * Reads an alert email (already from a listed address, already decoded to text) into the same facts an SMS gives: amount, debit or
 * credit, who, the last four digits, the reference number. Pure and on the phone; the email never goes anywhere.
 *
 * Because an email's sender cannot be fully proven on the phone (DKIM and SPF are the provider's to check), a mail only counts when
 * its text is worded like the bank's own alert. Anything from a listed address that mentions money moving but is not worded that way
 * is not counted: it is kept as a masked snippet in "Messages not understood" so the person can share it for the developer to read.
 *
 * THE WORDINGS BELOW WERE WRITTEN FROM WHAT HDFC ALERT EMAILS ARE REPORTED TO SAY. They are NOT checked against real emails (the
 * build environment has none), and a bank may change them at any time. Treat every one as a first guess to be tuned from samples.
 */
class EmailAlertParser(private val smsParser: BankMessageParser = BankMessageParser()) {

    /** @param key a stable id for this email, see [EmailAlerts.keyOf]. */
    fun read(sender: AlertSender, subject: String, body: String, at: Long, key: String): EmailRead {
        return try {
            readUnchecked(sender, subject, body.take(MAX_BODY).replace('\u00a0', ' ').replace("\r\n", "\n"), at, key)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Untrusted text: whatever goes wrong, nothing is counted.
            EmailRead.Skipped(SkipReason.NOT_A_PAYMENT)
        }
    }

    private fun readUnchecked(sender: AlertSender, subject: String, lined: String, at: Long, key: String): EmailRead {
        val text = flat(lined)
        fun alert(kind: EmailAlertKind, amount: String, payee: String, vpa: String, account: String, isCard: Boolean, ref: String, snippet: String = "") =
            EmailAlert(
                key = key, senderId = sender.id, bank = sender.bank, senderName = sender.name, at = at, kind = kind,
                amount = amount, payee = payee.take(MAX_PAYEE), vpa = vpa.take(MAX_PAYEE), account = account, isCard = isCard,
                reference = ref, snippet = snippet.take(EmailAlert.SNIPPET_MAX)
            )

        // The bank's own wordings first: they are exact, so a word like "offer" in a footer cannot make them look like marketing.
        UPI_DEBIT.find(text)?.let { m ->
            val vpa = m.groupValues[3]
            val name = nameOf(m.groupValues[4], m.groupValues[5])
            return EmailRead.Understood(alert(EmailAlertKind.DEBIT, amount(m.groupValues[1]), name.ifEmpty { vpa }, vpa, last4(m.groupValues[2]), false, reference(text)))
        }
        UPI_CREDIT.find(text)?.let { m ->
            val vpa = m.groupValues[3]
            val name = nameOf(m.groupValues[4], m.groupValues[5])
            return EmailRead.Understood(alert(EmailAlertKind.CREDIT, amount(m.groupValues[1]), name.ifEmpty { vpa }, vpa, last4(m.groupValues[2]), false, reference(text)))
        }
        CARD_DEBITED.find(text)?.let { m ->
            return EmailRead.Understood(alert(EmailAlertKind.DEBIT, amount(m.groupValues[1]), clean(m.groupValues[3]), "", last4(m.groupValues[2]), true, reference(text)))
        }
        CARD_USED.find(text)?.let { m ->
            return EmailRead.Understood(alert(EmailAlertKind.DEBIT, amount(m.groupValues[2]), clean(m.groupValues[3]), "", last4(m.groupValues[1]), true, reference(text)))
        }

        // What is certainly not a payment.
        if (STATEMENT.containsMatchIn(subject) || STATEMENT.containsMatchIn(text.take(STATEMENT_LOOK))) return EmailRead.Skipped(SkipReason.STATEMENT)
        if (OtpParser.isOtp(text)) return EmailRead.Skipped(SkipReason.OTP)
        if (MARKETING.containsMatchIn(subject)) return EmailRead.Skipped(SkipReason.MARKETING)

        val money = GenericTransactionParser.mentionsMoney(text)
        if (!money) return EmailRead.Skipped(SkipReason.NOT_A_PAYMENT)

        // The SMS reader's own formats and its best-effort pass, when the wording is one an SMS from this bank would use too.
        val tx = if (MOVEMENT_WIDE.containsMatchIn(text)) smsParser.parse(lined) ?: smsParser.parse(text) else null
        if (tx != null && tx.amount.isNotEmpty() && (tx.type == BankTransactionType.SENT || tx.type == BankTransactionType.RECEIVED) && !tx.isInternal) {
            val received = tx.type == BankTransactionType.RECEIVED
            val vpa = UPI_ID.find(tx.payee)?.value.orEmpty()
            return EmailRead.Understood(
                alert(
                    if (received) EmailAlertKind.CREDIT else EmailAlertKind.DEBIT, amount(tx.amount), clean(tx.payee), vpa,
                    last4(tx.accountNumber), CARD.containsMatchIn(text), reference(text)
                )
            )
        }

        // About money moving, in a wording not known: keep a masked snippet and nothing else.
        if (MOVEMENT.containsMatchIn(text) || TRANSACTION_WORDS.containsMatchIn(text)) {
            return EmailRead.NotUnderstood(alert(EmailAlertKind.UNCLEAR, "", "", "", "", false, "", snippetOf(text)))
        }
        return EmailRead.Skipped(SkipReason.NOT_A_PAYMENT)
    }

    /** The part of the text around the first mention of money, masked like the parsing report masks a message. */
    fun snippetOf(text: String): String {
        val at = MONEY.find(text)?.range?.first ?: 0
        val from = (at - SNIPPET_BEFORE).coerceAtLeast(0)
        val raw = text.substring(from, (from + EmailAlert.SNIPPET_MAX).coerceAtMost(text.length))
        return MessageRedactor.redact(raw).take(EmailAlert.SNIPPET_MAX)
    }

    private fun flat(body: String) = SPACES.replace(body.replace('\u00a0', ' '), " ").trim()
    private fun amount(raw: String) = raw.replace(",", "").trim()
    private fun last4(raw: String) = raw.filter { it.isDigit() }.takeLast(LAST_DIGITS)
    private fun clean(raw: String) = SPACES.replace(raw, " ").trim(' ', '.', ',', ':', '-')
    private fun nameOf(inBrackets: String, afterVpa: String) = clean(inBrackets.ifEmpty { afterVpa })
    private fun reference(text: String): String =
        REFERENCE.find(text)?.groupValues?.get(1)
            ?: com.zyke.message.domain.ledger.SamePayment.transactionRefs(text).firstOrNull().orEmpty()

    private companion object {
        const val MAX_BODY = 20_000
        const val MAX_PAYEE = 80
        const val LAST_DIGITS = 4
        const val STATEMENT_LOOK = 400
        const val SNIPPET_BEFORE = 60

        val SPACES = Regex("\\s+")
        const val AMT = "(?:Rs\\.?|INR|\u20b9)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
        const val DATE = "\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}"
        const val ACCOUNT = "(?:account|a/c|acct)\\s*(?:no\\.?|number)?\\s*(?:ending\\s+(?:with\\s+)?)?[x*]*\\s*(\\d{2,4})"
        // "name@bank (Merchant)" or "name@bank MERCHANT NAME" before "on <date>". Groups: 3 the UPI address, 4 the name in brackets, 5 the name after the address
        // (1 is the amount and 2 the account, from the parts the patterns below put in front).
        const val VPA_AND_NAME = "VPA\\s+(\\S+@[\\w.]+?)(?:\\s*\\(([^)]{1,60})\\)|\\s+([A-Za-z][^.]{0,60}?))?\\s+on\\s+$DATE"

        /** "Rs.250.00 has been debited from account **1234 to VPA name@bank (Merchant) on 28-11-26." */
        val UPI_DEBIT = Regex("(?i)$AMT\\s+(?:has been|was|is)\\s+(?:successfully\\s+)?debited\\s+from\\s+(?:your\\s+)?(?:HDFC Bank\\s+)?$ACCOUNT\\s+to\\s+$VPA_AND_NAME")

        /** "Rs. 500.00 is successfully credited to your account **1234 by VPA name@bank (Sender) on 28-11-26." */
        val UPI_CREDIT = Regex("(?i)$AMT\\s+(?:has been|was|is)\\s+(?:successfully\\s+)?credited\\s+to\\s+(?:your\\s+)?(?:HDFC Bank\\s+)?$ACCOUNT\\s+(?:by|from)\\s+$VPA_AND_NAME")

        /** "Rs. 1500.00 was debited from your HDFC Bank Credit Card ending 1234 towards Merchant on 28 Nov, 2026 at 14:35:10." */
        val CARD_DEBITED = Regex(
            "(?i)$AMT\\s+(?:has been|was|is)\\s+debited\\s+from\\s+your\\s+(?:HDFC Bank\\s+)?(?:Credit|Debit)\\s+Card\\s+(?:ending\\s+(?:with\\s+)?)?[x*]*\\s*(\\d{4})\\s+towards\\s+(.{1,60}?)(?=\\s+on\\s+\\d)"
        )

        /** "Thank you for using your HDFC Bank Credit Card ending 1234 for Rs 1,500.00 at MERCHANT on 28-11-2026 14:35:10." */
        val CARD_USED = Regex(
            "(?i)thank you for using your\\s+(?:HDFC Bank\\s+)?(?:Credit|Debit)\\s+Card\\s+(?:ending\\s+(?:with\\s+)?)?[x*]*\\s*(\\d{4})\\s+for\\s+$AMT\\s+at\\s+(.{1,60}?)(?=\\s+on\\s+\\d)"
        )

        val REFERENCE = Regex("(?i)(?:UPI\\s+)?(?:transaction\\s+)?reference\\s+(?:number|no\\.?)\\s*(?:is|:)?\\s*(\\d{9,16})")
        val UPI_ID = Regex("[A-Za-z0-9._-]{2,}@[A-Za-z][A-Za-z0-9.]{1,}")
        val CARD = Regex("(?i)\\bcard\\b")
        val MONEY = Regex("(?i)(?:Rs\\.?|INR|\u20b9)\\s*[0-9]")
        val MOVEMENT = Regex("(?i)\\b(?:debited|credited|spent|withdrawn|deposited)\\b")
        val MOVEMENT_WIDE = Regex("(?i)\\b(?:debited|credited|spent|withdrawn|deposited|sent|paid|received|transferred)\\b")
        val TRANSACTION_WORDS = Regex("(?i)\\b(?:transaction|txn|upi|neft|imps|rtgs|payment (?:of|received|made|successful))\\b")
        val STATEMENT = Regex("(?i)\\b(?:e-?statement|account statement|statement (?:for|of|is|has)|smart ?statement|monthly statement)\\b")
        val MARKETING = Regex("(?i)\\b(?:offer|cashback|reward points|win|apply now|pre-?approved|upgrade|newsletter|invite|discount|sale|limited time)\\b")
    }
}
