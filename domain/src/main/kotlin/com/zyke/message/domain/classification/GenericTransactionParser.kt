package com.zyke.message.domain.classification

import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.BankTransaction
import com.zyke.message.domain.model.BankTransactionType
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

        val body = message.replace(Regex("\\s+"), " ").trim()
        return parseScheduled(body) ?: parseBill(body) ?: parseMovement(body)
    }

    private fun parseMovement(body: String): BankTransaction? {
        val direction = firstKeyword(body) ?: return null
        val amount = amountNear(body, direction) ?: return null
        val received = direction.second in CREDIT_WORDS

        return BankTransaction(
            bankName = bank(body),
            accountNumber = account(body).orEmpty(),
            payee = payee(body, received).orEmpty(),
            amount = amount,
            type = if (received) BankTransactionType.RECEIVED else BankTransactionType.SENT
        )
    }

    /** "INR 650 will be debited on 28/10/2026 ... for Google Play" or "Mandate Set Rs.499 For Google Play". */
    private fun parseScheduled(body: String): BankTransaction? {
        val type = when {
            UPCOMING.containsMatchIn(body) -> BankTransactionType.UPCOMING_DEBIT
            MANDATE.containsMatchIn(body) -> BankTransactionType.MANDATE_SET
            else -> return null
        }
        val amount = AMOUNT.find(body)?.groupValues?.get(1)?.let(::clean) ?: return null

        return BankTransaction(
            bankName = bank(body),
            accountNumber = account(body).orEmpty(),
            payee = FOR_PAYEE.find(body)?.groupValues?.get(1)?.let(::tidy).orEmpty(),
            amount = amount,
            type = type,
            dueDate = if (type == BankTransactionType.UPCOMING_DEBIT) {
                ON_DATE.find(body)?.groupValues?.get(1)?.let(::parseDate)
            } else {
                null
            }
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
            paymentLink = LINK.find(body)?.value
        )
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
                val lead = body.substring((match.range.first - 25).coerceAtLeast(0), match.range.first)
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
        private const val CURRENCY = "(?:INR\\.?|Rs\\.?|₹)"
        private const val NUMBER = "(\\d[\\d,]*(?:\\.\\d+)?)"

        private val MONEY_HINT = Regex("(?i)\\bINR|\\bRs(?![a-z])|₹")

        /** Cheap check used to decide whether a message is worth sending to the bank parsers. */
        fun mentionsMoney(body: String) = MONEY_HINT.containsMatchIn(body)

        private val CREDIT_WORDS = setOf("credited", "received", "deposited", "refund", "refunded")

        private val KEYWORDS = Regex(
            "(?i)\\b(?:debited|spent|paid|sent|withdrawn|purchase|transferred|credited|received|deposited|refunded|refund)\\b"
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
            Regex("(?i)\\bat\\s+$NAME$NAME_END")
        )
        private val FROM_PATTERNS = listOf(
            Regex("(?i)\\b(?:received from|from vpa|from)\\s+$NAME$NAME_END"),
            Regex("(?i)\\b(?:credited by|deposited by|by)\\s+$NAME$NAME_END"),
            Regex("(?i)\\binfo[:\\-]\\s*$NAME$NAME_END")
        )

        private val UPCOMING = Regex("(?i)\\bwill be (?:auto[- ]?)?debited")
        private val MANDATE = Regex("(?i)\\bmandate\\s+(?:set|created|registered|activated|successful|approved)")
        private val ON_DATE = Regex("(?i)\\bon\\s+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})")
        private val FOR_PAYEE = Regex(
            "(?i)\\bfor\\s+([A-Za-z0-9][A-Za-z0-9&.' ]{1,40}?)(?=\\s+-\\s|\\s+ID:|\\.\\.|\\s+Act:|\\s+from\\b|\\.?$)"
        )

        private val UPI_MARKERS = setOf("UPI", "P2M", "P2P", "P2A", "IMPS", "NEFT", "RTGS")
        private val REJECTED_PREFIXES = listOf("a/c", "ac ", "acct", "account", "your", "the ", "you", "card", "bank", "sbi", "hdfc")

        private val BILL_HINT = Regex("(?i)(?:statement|total (?:amt|amount) due|amount due|payment due|bill (?:is )?(?:generated|due))")
        private val BILL_TOTAL = Regex("(?i)(?:total (?:amt|amount)?\\s*due|(?:amt|amount) due|total due|outstanding)\\W{0,12}(?:of )?$CURRENCY?\\s*$NUMBER")
        private val BILL_MIN = Regex("(?i)min(?:imum)?(?: (?:amt|amount))?(?: due)?\\W{0,12}(?:of )?$CURRENCY?\\s*$NUMBER")
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
