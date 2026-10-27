package com.zyke.message.domain.classification

import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.BankTransaction
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.insights.RefundTracker
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Best-effort reader for bank SMS that the dedicated parsers don't know.
 * It looks for a debit / credit word, the amount next to it, and whatever payee,
 * account and bank hints it can find. Returns null when it isn't confident it is
 * looking at a real money movement (balance alerts, offers, ...).
 */
class GenericTransactionParser {

    fun parse(message: String): BankTransaction? {
        if (PromoDetector.hasPromoHints(message)) return null
        // "Refund of Rs 500 initiated" is a promise, not money received yet.
        if (RefundTracker.announcesPendingRefund(message.replace(Regex("\\s+"), " "))) return null

        val body = message.replace(Regex("\\s+"), " ").trim()
        return parseTemplate(body) ?: parseScheduled(body) ?: parseBill(body) ?: parseProcessed(body) ?: parsePaymentMade(body) ?: parseMovement(body)
    }

    /** Fixed-format messages (autopay, SIPs, netbanking, ATM, ...) that carry no debit / credit keyword. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun parseTemplate(body: String): BankTransaction? {
        NACH_DEBIT.find(body)?.let {
            return BankTransaction(
                bank(body), it.groupValues[3], it.groupValues[1].let(::tidy).orEmpty(), clean(it.groupValues[2]), BankTransactionType.SENT
            )
        }
        ACH_DEBIT.find(body)?.let {
            return BankTransaction(
                bank(body), it.groupValues[2], it.groupValues[3].let(::tidy).orEmpty(), clean(it.groupValues[1]), BankTransactionType.SENT
            )
        }
        ACCOUNT_DEBITED_TOWARDS.find(body)?.let {
            return BankTransaction(
                bank(body), "", it.groupValues[1].let(::tidy).orEmpty(), clean(it.groupValues[2]), BankTransactionType.SENT
            )
        }
        EMI_DUE.find(body)?.let {
            return BankTransaction(
                bank(body), "", it.groupValues[2].let(::tidy).orEmpty(), clean(it.groupValues[1]), BankTransactionType.UPCOMING_DEBIT,
                dueDate = parseDate(it.groupValues[3])
            )
        }
        MANDATE_AS_PRESENTED.find(body)?.let {
            return BankTransaction(
                bank(body), "", it.groupValues[1].let(::tidy).orEmpty(), clean(it.groupValues[2]), BankTransactionType.MANDATE_SET
            )
        }
        SIP_PROCESSED.find(body)?.let {
            return BankTransaction(BankName.MUTUAL_FUND, "", it.groupValues[1], clean(it.groupValues[2]), BankTransactionType.SENT)
        }
        SIP_DUE.find(body)?.let {
            return BankTransaction(
                BankName.MUTUAL_FUND, "", "SIP instalment", clean(it.groupValues[1]), BankTransactionType.UPCOMING_DEBIT,
                dueDate = it.groupValues[2].let(::parseDate)
            )
        }
        AUTOPAY_SUCCESS.find(body)?.let {
            val payee = it.groupValues[1].substringBefore(" - ").substringBefore("..").trim()
            return BankTransaction(
                bank(body), it.groupValues[3], payee, clean(it.groupValues[2]), BankTransactionType.SENT
            )
        }
        AUTOPAY_ACTIVATED.find(body)?.let {
            return BankTransaction(
                bank(body), it.groupValues[3], it.groupValues[2].trim(), clean(it.groupValues[1]), BankTransactionType.MANDATE_SET
            )
        }
        EMANDATE_DEDUCTION.find(body)?.let {
            return BankTransaction(
                bank(body), "", it.groupValues[3].let(::tidy).orEmpty(), clean(it.groupValues[1]), BankTransactionType.UPCOMING_DEBIT,
                dueDate = parseDate(it.groupValues[2])
            )
        }
        DEDUCTED_FROM_ACCOUNT.find(body)?.let {
            return BankTransaction(
                bank(body), it.groupValues[2], it.groupValues[3].let(::tidy).orEmpty(), clean(it.groupValues[1]), BankTransactionType.SENT
            )
        }
        NETBANKING_PAYMENT.find(body)?.let {
            return BankTransaction(
                bank(body), it.groupValues[2], it.groupValues[3].let(::tidy).orEmpty(), clean(it.groupValues[1]), BankTransactionType.SENT
            )
        }
        UPI_LITE_TOP_UP.find(body)?.let {
            return BankTransaction(bank(body), "", "UPI Lite", clean(it.groupValues[1]), BankTransactionType.SENT)
        }
        WITHDRAWN.find(body)?.let {
            val bank = if (body.contains("IndianBank", ignoreCase = true) || body.contains("Indian Bank", ignoreCase = true)) {
                BankName.INDIAN_BANK
            } else {
                bank(body)
            }
            return BankTransaction(bank, it.groupValues[2], ATM_PAYEE, clean(it.groupValues[1]), BankTransactionType.SENT)
        }
        POS_RECEIPT.find(body)?.let {
            return BankTransaction(
                BankName.OTHERS, "", it.groupValues[1].let(::tidy).orEmpty(), clean(it.groupValues[2]), BankTransactionType.SENT
            )
        }
        UTILITY_BILL.find(body)?.let {
            return BankTransaction(
                BankName.OTHERS, "", it.groupValues[3].let(::tidy) ?: "Electricity", clean(it.groupValues[1]),
                BankTransactionType.UPCOMING_DEBIT, dueDate = it.groupValues[2].let(::parseDate)
            )
        }
        AIRTEL_RECHARGE.find(body)?.let {
            return BankTransaction(BankName.OTHERS, "", "Airtel recharge", clean(it.groupValues[1]), BankTransactionType.SENT)
        }
        ORDER_AMOUNT.find(body)?.let {
            return BankTransaction(BankName.OTHERS, "", it.groupValues[1], clean(it.groupValues[2]), BankTransactionType.SENT)
        }
        BOOKING_CONFIRMED.find(body)?.let {
            return BankTransaction(BankName.OTHERS, "", "Flight booking", clean(it.groupValues[1]), BankTransactionType.SENT)
        }
        BROADBAND_BILL.find(body)?.let {
            val payee = if (body.contains("skylink", ignoreCase = true)) "Skylink" else "Bill"
            return BankTransaction(
                BankName.OTHERS, "", payee, clean(it.groupValues[1]), BankTransactionType.UPCOMING_DEBIT,
                dueDate = it.groupValues[2].takeIf(String::isNotEmpty)?.let(::parseDate)
            )
        }
        INSURANCE_PREMIUM.find(body)?.let {
            return BankTransaction(BankName.OTHERS, "", "Insurance premium", clean(it.groupValues[1]), BankTransactionType.SENT)
        }
        return null
    }

    private fun parseMovement(body: String): BankTransaction? {
        val direction = firstKeyword(body) ?: return null
        val amount = amountNear(body, direction) ?: return null
        val received = direction.second in CREDIT_WORDS

        return BankTransaction(
            bankName = bank(body),
            accountNumber = account(body).orEmpty(),
            payee = payee(body, received) ?: ATM_PAYEE.takeIf { ATM.containsMatchIn(body) }.orEmpty(),
            amount = amount,
            type = if (received) BankTransactionType.RECEIVED else BankTransactionType.SENT
        )
    }

    /** "INR 650 will be debited on 28/10/2026 ... for Google Play" or "Mandate Set Rs.499 For Google Play". */
    private fun parseScheduled(body: String): BankTransaction? {
        val type = when {
            MANDATE_CANCELLED.containsMatchIn(body) -> BankTransactionType.MANDATE_CANCELLED
            MANDATE_REQUEST.containsMatchIn(body) -> BankTransactionType.MANDATE_REQUEST
            UPCOMING.containsMatchIn(body) -> BankTransactionType.UPCOMING_DEBIT
            MANDATE.containsMatchIn(body) || MANDATE_CONFIRMED.containsMatchIn(body) -> BankTransactionType.MANDATE_SET
            else -> return null
        }
        val amount = AMOUNT.find(body)?.groupValues?.get(1)?.let(::clean) ?: return null

        return BankTransaction(
            bankName = bank(body),
            accountNumber = account(body).orEmpty(),
            payee = scheduledPayee(body),
            amount = amount,
            type = type,
            dueDate = if (type == BankTransactionType.UPCOMING_DEBIT) {
                ON_DATE.find(body)?.groupValues?.get(1)?.let(::parseDate)
            } else {
                null
            }
        )
    }

    private fun scheduledPayee(body: String): String =
        (MANDATE_TO.find(body) ?: MANDATE_REQUEST.find(body) ?: MANDATE_CONFIRMED.find(body) ?: FOR_PAYEE.find(body))
            ?.groupValues?.last()?.let(::tidy).orEmpty()

    /** "we have processed Rs. 299 for your Airtel Mobile 98xxxxxx". */
    private fun parseProcessed(body: String): BankTransaction? {
        val match = PROCESSED.find(body) ?: return null
        return BankTransaction(
            bankName = bank(body),
            accountNumber = "",
            payee = tidy(match.groupValues[2]).orEmpty(),
            amount = clean(match.groupValues[1]),
            type = BankTransactionType.SENT
        )
    }

    /** "Payment of Rs.500 towards ... On Some Trust successfully made ... - Razorpay". */
    private fun parsePaymentMade(body: String): BankTransaction? {
        val amount = PAYMENT_MADE.find(body)?.groupValues?.get(1) ?: return null
        return BankTransaction(
            bankName = bank(body),
            accountNumber = account(body).orEmpty(),
            payee = PAYMENT_MADE_PAYEE.find(body)?.groupValues?.get(1)?.let(::tidy).orEmpty(),
            amount = clean(amount),
            type = BankTransactionType.SENT
        )
    }

    private fun parseBill(body: String): BankTransaction? {
        if (!BILL_HINT.containsMatchIn(body)) return null
        val total = BILL_TOTAL.find(body)?.groupValues?.get(1) ?: return null

        return BankTransaction(
            bankName = bank(body),
            accountNumber = account(body).orEmpty(),
            payee = "",
            amount = total,
            type = BankTransactionType.CARD_BILL,
            dueDate = BILL_DUE.find(body)?.groupValues?.get(1)?.let(::parseDate),
            minimumDue = BILL_MIN.find(body)?.groupValues?.get(1),
            paymentLink = paymentLink(body)
        )
    }

    /** The "Pay at" address when the message gives one (often without a scheme), else the first link. */
    private fun paymentLink(body: String): String? {
        val payAt = PAY_AT.find(body)?.groupValues?.get(1)?.trimEnd('.', ',')
        if (payAt != null) return if (payAt.startsWith("http", ignoreCase = true)) payAt else "https://$payAt"
        return LINK.find(body)?.value
    }

    /** The earliest debit / credit word and its position. */
    private fun firstKeyword(body: String): Pair<Int, String>? =
        KEYWORDS.findAll(body).firstOrNull()?.let { it.range.first to it.value.lowercase() }

    /**
     * Prefers the amount right after the keyword ("debited by Rs 500") or right before it
     * ("Rs 500 debited"); balances and limits are never picked.
     */
    private fun amountNear(body: String, keyword: Pair<Int, String>): String? {
        val after = AMOUNT_AFTER.find(body.substring(keyword.first))
        if (after != null && !BALANCE_WORDS.containsMatchIn(after.value)) return clean(after.groupValues[1])

        val before = AMOUNT_BEFORE.find(body)
        if (before != null) return clean(before.groupValues[1])

        // Last resort: the first amount that is not a balance / limit.
        return AMOUNT.findAll(body)
            .firstOrNull { match ->
                val lead = body.substring((match.range.first - BALANCE_LOOKBEHIND).coerceAtLeast(0), match.range.first)
                !NOT_THE_AMOUNT.containsMatchIn(lead)
            }?.groupValues?.get(1)?.let(::clean)
    }

    private fun clean(raw: String) = raw.replace(",", "").trimEnd('.')

    private fun account(body: String): String? =
        MASKED_ACCOUNT.find(body)?.groupValues?.get(1)
            ?: ENDING_ACCOUNT.find(body)?.groupValues?.get(1)
            ?: LABELLED_ACCOUNT.find(body)?.groupValues?.get(1)

    private fun payee(body: String, received: Boolean): String? {
        UPI_ID.find(body)?.value?.let { return it }

        // ICICI style: "...; SWIGGY credited."
        if (!received) SEMICOLON_CREDITED.find(body)?.groupValues?.get(1)?.let(::tidy)?.let { return it }

        val patterns = if (received) FROM_PATTERNS else TO_PATTERNS
        for (pattern in patterns) {
            val name = pattern.find(body)?.groupValues?.get(1)?.let(::tidy)
            if (name != null) return name
        }
        return null
    }

    /** Trims noise from a captured payee and rejects things that are obviously not a name. */
    private fun tidy(raw: String): String? {
        var name = raw.trim().trim('.', ',', ';', ':', '-', ' ')
        // "UPI/P2M/123456789/ZOMATO" -> "ZOMATO"
        if ('/' in name) {
            name = name.split('/').lastOrNull { part -> part.any { it.isLetter() } && part.uppercase() !in UPI_MARKERS }.orEmpty()
        }
        val lower = name.lowercase()
        val rejected = name.length < 2 ||
            name.uppercase() in UPI_MARKERS ||
            name.none { it.isLetter() } ||
            REJECTED_PREFIXES.any { lower.startsWith(it) }
        return name.takeUnless { rejected }
    }

    private fun bank(body: String): BankName =
        BANK_HINTS.firstOrNull { (hint, _) -> hint.containsMatchIn(body) }?.second ?: BankName.OTHERS

    private fun parseDate(raw: String): LocalDate? {
        val text = raw.trim()
        return DATE_FORMATS.firstNotNullOfOrNull { fmt -> runCatching { LocalDate.parse(text, fmt) }.getOrNull() }
    }

    companion object {
        /** How far back to look for words like "balance" or "limit" before an amount. */
        private const val BALANCE_LOOKBEHIND = 25
        private const val CURRENCY = "(?:INR\\.?|Rs\\.?|₹)"
        private const val NUMBER = "(\\d[\\d,]*(?:\\.\\d+)?)"
        /** Axis writes "INR Dr. 1,234.00". */
        private const val DR = "(?:Dr\\.?\\s*)?"
        private const val ATM_PAYEE = "ATM cash"

        private val MONEY_HINT = Regex("(?i)\\bINR|\\bRs(?![a-z])|₹")

        /** Cheap check used to decide whether a message is worth sending to the bank parsers. */
        fun mentionsMoney(body: String) = MONEY_HINT.containsMatchIn(body)

        private val CREDIT_WORDS = setOf("credited", "received", "deposited", "refund", "refunded")

        private val KEYWORDS = Regex(
            "(?i)\\b(?:debited|spent|paid|sent|withdrawn|withdrawal|purchase|transferred|credited|received|deposited|refunded|refund)\\b"
        )

        private val AMOUNT = Regex("(?i)$CURRENCY\\s*$NUMBER")
        private val AMOUNT_AFTER = Regex("(?i)^[A-Za-z]+(?:\\s+(?:by|for|of|with|an amount of|amount|amt|:))?[^\\d₹]{0,25}?$CURRENCY\\s*$NUMBER")
        private val AMOUNT_BEFORE = Regex(
            "(?i)$CURRENCY\\s*$NUMBER\\s*(?:has been |was |is |got )?(?:successfully )?" +
                "(?:debited|credited|spent|paid|sent|withdrawn|received|deposited|transferred|refunded)"
        )
        private val BALANCE_WORDS = Regex("(?i)\\b(?:bal|balance|avl|avail|available|limit)\\b")
        private val NOT_THE_AMOUNT = Regex("(?i)(?:bal(?:ance)?|avl|avail(?:able)?|limit|min(?:imum)?|due|outstanding)\\W*(?:\\w+\\W+){0,2}$")

        private val MASKED_ACCOUNT = Regex("(?i)[xX*•]{2,}\\s*(\\d{3,6})\\b")
        private val ENDING_ACCOUNT = Regex("(?i)(?:ending(?: with| in)?|last (?:4 )?digits(?: of)?)\\s*[:\\-]?\\s*(\\d{3,6})\\b")
        private val LABELLED_ACCOUNT = Regex("(?i)(?:a/c|acct?|account|card)(?:\\s*(?:no\\.?|number))?\\s*[:\\-]?\\s*[xX*]*(\\d{4,6})\\b")

        private val UPI_ID = Regex("[a-zA-Z0-9._\\-]{2,}@[a-zA-Z]{2,}")
        private val SEMICOLON_CREDITED = Regex(";\\s*([A-Za-z0-9&.' @_-]{2,40}?)\\s+credited", RegexOption.IGNORE_CASE)

        private const val NAME = "([A-Za-z0-9][A-Za-z0-9&.'/@_ -]{1,40}?)"
        private const val NAME_END = "(?=\\s+(?:on|via|using|ref|refno|upi|txn|vpa|avl|available|bal|balance|if|call|dated|at|from|for|thru|through|\\()|[.,;]\\s|\\.?$)"

        private val TO_PATTERNS = listOf(
            Regex("(?i)\\b(?:trf to|transfer to|paid to|sent to|towards|to vpa|to)\\s+$NAME$NAME_END"),
            Regex("(?i)\\binfo[:\\-]\\s*$NAME$NAME_END"),
            Regex("(?i)\\bat(?:\\s+|\\s*:\\s*)$NAME$NAME_END")
        )
        private val FROM_PATTERNS = listOf(
            Regex("(?i)\\b(?:received from|from vpa|from)\\s+$NAME$NAME_END"),
            Regex("(?i)\\b(?:credited by|deposited by|by)\\s+$NAME$NAME_END"),
            Regex("(?i)\\binfo[:\\-]\\s*$NAME$NAME_END")
        )

        private val MANDATE_CANCELLED = Regex("(?i)\\bmandate\\b[^.]{0,80}?\\b(?:cancel(?:l)?ed|revoked)\\b")
        private val MANDATE_TO = Regex("(?i)\\bmandate to\\s+(.+?)\\s+has been\\b")
        private val MANDATE_REQUEST = Regex(
            "(?i)\\bUPI request of\\s+$CURRENCY\\s*$NUMBER\\s+from\\s+(.+?)(?=\\s+(?:your|on|open|use|via)\\b|[.,;]|$)"
        )
        /** HDFC: "UPI-Mandate: For Rs. 199.00 To NETFLIX A/c x1234 Ref ...". */
        private val MANDATE_CONFIRMED = Regex("(?i)\\bUPI[- ]Mandate:?\\s*For\\s+$CURRENCY\\s*$NUMBER\\s+To\\s+(.+?)\\s+A/c\\b")
        private val PROCESSED = Regex("(?i)\\bprocessed\\s+$CURRENCY\\s*$NUMBER\\s+for your\\s+(.+?)(?=\\s+\\d{6,}|[.,]|$)")
        private val PAYMENT_MADE = Regex("(?i)\\bpayment of\\s+$CURRENCY\\s*$NUMBER\\b.*?\\bsuccessfully made\\b")
        private val PAYMENT_MADE_PAYEE = Regex("\\bOn\\s+([A-Za-z][A-Za-z0-9&.' ]{1,40}?)\\s+successfully made\\b")
        private val UPCOMING = Regex("(?i)\\bwill be (?:auto[- ]?)?(?:debited|deducted)")
        private val MANDATE = Regex("(?i)\\bmandate\\s+(?:set|created|registered|activated|successful|approved)")
        private val ON_DATE = Regex("(?i)\\bon\\s+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})")
        private val FOR_PAYEE = Regex(
            "(?i)\\bfor\\s+([A-Za-z0-9][A-Za-z0-9&.' ]{1,40}?)(?=\\s+-\\s|\\s+ID:|\\.\\.|\\s+Act:|\\s+from\\b|\\.?$)"
        )

        /** "NACH debit towards X for INR 1,234 with UMRN ... successfully processed in A/c no. XX1234". */
        private val NACH_DEBIT = Regex(
            "(?i)\\bNACH debit towards\\s+(.+?)\\s+for\\s+$CURRENCY\\s*$NUMBER\\b.*?\\bA/c(?: no\\.?)?\\s*[xX*]*(\\d+)"
        )
        /** "Debit INR 1,234 Axis Bank A/c XX1234 10-10-26 10:00:00 ACH-DR-PAYEE ...". */
        private val ACH_DEBIT = Regex(
            "(?i)^\\s*Debit\\s+$CURRENCY\\s*$NUMBER\\b.*?\\bA/c\\s*[xX*]*(\\d+)\\b.*?\\bACH-DR-(.+?)(?=\\s+(?:WhatsApp|Not You|Avl|Bal)\\b|$)"
        )
        /** "Your A/c has been debited towards X for INR 123 on 10-10-26." */
        private val ACCOUNT_DEBITED_TOWARDS = Regex("(?i)\\bA/c has been debited towards\\s+(.+?)\\s+for\\s+$CURRENCY\\s*$NUMBER\\b")
        /** "EMI of Rs. 1234 towards LOAN is due on 05/11/2026." */
        private val EMI_DUE = Regex(
            "(?i)\\bEMI of\\s+$CURRENCY\\s*$NUMBER\\s+towards\\s+(.+?)\\s+is due on\\s+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})"
        )
        /** "UPI ASPRESENTED mandate has been successfully created towards X from 10-10-26 to 10-10-27 for INR 1234". */
        private val MANDATE_AS_PRESENTED = Regex(
            "(?i)\\bmandate has been successfully created towards\\s+(.+?)\\s+from\\s+.*?\\bfor\\s+$CURRENCY\\s*$NUMBER\\b"
        )
        private val SIP_PROCESSED = Regex("(?i)\\bSIP (?:in \\d+|under folio \\S+) in (.+?) for $CURRENCY\\s*$NUMBER is processed")
        private val SIP_DUE = Regex("(?i)\\bSIP instal?ment\\b.*?for $CURRENCY\\s*$NUMBER is due for debit.*? on (\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})")
        private val AUTOPAY_SUCCESS = Regex(
            "(?i)AutoPay \\(E-mandate\\) Success!\\s*For (.*?)\\s*Txn Amt:\\s*$CURRENCY\\s*$NUMBER.*?\\bCC (\\d+)"
        )
        private val AUTOPAY_ACTIVATED = Regex(
            "(?i)AutoPay activation successful\\.?\\s*Txn amt:\\s*$CURRENCY\\s*$NUMBER\\s*Merchant:\\s*(.+?)\\s+\\w+ Bank Card no\\.?:\\s*[xX*]*(\\d+)"
        )
        private val EMANDATE_DEDUCTION = Regex(
            "(?i)E-Mandate!\\s*$CURRENCY\\s*$NUMBER will be deducted on (\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})[^A-Za-z]*For (.+?) mandate"
        )
        private val DEDUCTED_FROM_ACCOUNT = Regex(
            "(?i)PAYMENT ALERT!\\s*$CURRENCY\\s*$NUMBER deducted from .*?A/C No (\\d+) towards (.+?)(?=\\s+UMRN|:|$)"
        )
        private val NETBANKING_PAYMENT = Regex(
            "(?i)Payment Successful!\\s*$CURRENCY\\s*$NUMBER from A/c [xX*]*(\\d+) to (.+?) via .*?NetBanking"
        )
        private val UPI_LITE_TOP_UP = Regex("(?i)UPI LITE Top-up amounting to $CURRENCY\\s*$NUMBER has been successful")
        private val WITHDRAWN = Regex("(?i)$CURRENCY\\s*$NUMBER w/d from A/c [xX*]*(\\d+)")
        private val POS_RECEIPT = Regex("(?i)POS Receipt:\\s*\\S+\\s+(.+?)\\s+$CURRENCY\\s*$NUMBER\\s*$")
        private val UTILITY_BILL = Regex(
            "(?i)Electricity charges of $CURRENCY\\s*$NUMBER.*? is due on (\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}).*?-\\s*(\\w+)\\s*$"
        )
        private val AIRTEL_RECHARGE = Regex("(?i)Recharge of $CURRENCY\\s*$NUMBER is successful for your Airtel")
        private val ORDER_AMOUNT = Regex("(?i)Thanks for choosing ([A-Za-z0-9' ]+?)\\..*?Amount:\\s*$CURRENCY\\s*$NUMBER")
        private val BOOKING_CONFIRMED = Regex("(?i)booking ID:\\s*\\w+ is successfully confirmed.*? for $CURRENCY\\s*$NUMBER")
        private val BROADBAND_BILL = Regex(
            "(?i)Bill Dated \\S+(?: of this month)? of $CURRENCY\\s*$NUMBER is (?:Due on (\\d{1,2}[-/ ][A-Za-z0-9]{1,3}[-/ ]\\d{2,4})|Over Due)"
        )
        private val INSURANCE_PREMIUM = Regex("(?i)\\bPremium $CURRENCY\\s*$NUMBER")

        private val UPI_MARKERS = setOf("UPI", "P2M", "P2P", "P2A", "IMPS", "NEFT", "RTGS")
        private val REJECTED_PREFIXES = listOf("a/c", "ac ", "acct", "account", "your", "the ", "you", "card", "bank", "sbi", "hdfc")

        private val BILL_HINT = Regex("(?i)(?:statement|total (?:amt|amount) due|amount due|payment due|bill (?:is )?(?:generated|due))")
        private val BILL_TOTAL = Regex(
            "(?i)(?:total (?:(?:amt|amount|payment)(?: due)?|due)|(?:amt|amount) due|outstanding)\\W{0,12}(?:of )?$CURRENCY?\\s*$DR$NUMBER"
        )
        private val BILL_MIN = Regex(
            "(?i)min(?:imum)?(?: (?:amt|amount|payment))?(?: due)?\\W{0,12}(?:of )?$CURRENCY?\\s*$DR$NUMBER"
        )
        private val PAY_AT = Regex("(?i)\\bpay at\\s+(\\S+)")
        private val ATM = Regex("\\bATM\\b")
        private val BILL_DUE = Regex("(?i)due (?:date|by|on)?\\W{0,3}(\\d{1,2}[-/ ](?:\\d{1,2}|[A-Za-z]{3})[-/ ]\\d{2,4})")
        private val LINK = Regex("https?://\\S+")

        private val DATE_FORMATS = listOf(
            "d-M-yyyy", "d-M-yy", "d/M/yyyy", "d/M/yy",
            "d-MMM-yyyy", "d-MMM-yy", "d MMM yyyy", "d MMM yy"
        ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

        private val BANK_HINTS: List<Pair<Regex, BankName>> = listOf(
            Regex("(?i)\\bHDFC\\b") to BankName.HDFC,
            Regex("(?i)\\bICICI\\b") to BankName.ICICI,
            Regex("(?i)\\bSBI\\b|State Bank") to BankName.SBI,
            Regex("(?i)\\bAxis\\b") to BankName.AXIS,
            Regex("(?i)\\bKotak\\b") to BankName.KOTAK,
            Regex("(?i)IDFC") to BankName.IDFC_FIRST,
            Regex("(?i)\\bYes Bank\\b") to BankName.YES_BANK,
            Regex("(?i)\\bPNB\\b|Punjab National") to BankName.PNB,
            Regex("(?i)\\bCanara\\b") to BankName.CANARA,
            Regex("(?i)Bank of Baroda|\\bBOB\\b") to BankName.BANK_OF_BARODA,
            Regex("(?i)IndusInd") to BankName.INDUSIND,
            Regex("(?i)Federal Bank") to BankName.FEDERAL_BANK,
            Regex("(?i)\\bPaytm\\b") to BankName.PAYTM,
            Regex("(?i)\\bAmex\\b|American Express") to BankName.AMERICAN_EXPRESS
        )
    }
}
