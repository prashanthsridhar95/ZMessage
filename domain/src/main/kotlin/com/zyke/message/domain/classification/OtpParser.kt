package com.zyke.message.domain.classification

/** Result of pulling the interesting bits out of a one-time-password SMS. */
data class ParsedOtp(
    val code: String,
    val senderName: String,
    val paymentAmount: String?,
    val cardNumber: String?
)

class OtpParser {

    fun parse(rawBody: String, sender: String): ParsedOtp {
        val body = withoutRetrieverHash(rawBody)
        val codeRegex = if (body.contains("Simpl", ignoreCase = true)) SIMPL_CODE else NUMERIC_CODE
        // "The OTP is 48213679 for Rs.15,000.00 TXN ... Txn Ref: 523412345678": the code is the one the OTP is said to be.
        val code = (if (codeRegex === NUMERIC_CODE) STATED_CODE.find(body)?.groupValues?.get(1) else null)
            ?: (if (codeRegex === NUMERIC_CODE) likelyCode(body) else null)
            ?: codeRegex.find(body)?.groupValues?.get(1).orEmpty()

        val senderName = if (body.contains("Bank")) {
            SENDER.find(body)?.value?.trim() ?: sender
        } else {
            sender
        }

        val cardNumber = CARD_NUMBER.find(body)?.groupValues?.get(1)
        val amount = AMOUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.trim()
        val hasPayment = cardNumber != null && amount != null
        // A bank's transfer OTP names the sum without a card: "for Rs.15,000.00 TXN to NAME".
        val transferAmount = if (hasPayment) null else TRANSFER_AMOUNT.find(body)?.groupValues?.get(1)?.let(::plainAmount)

        return ParsedOtp(
            code = code,
            senderName = senderName,
            paymentAmount = amount.takeIf { hasPayment } ?: transferAmount,
            cardNumber = cardNumber.takeIf { hasPayment }
        )
    }

    companion object {
        private val RETRIEVER_HASH = "\\s+[A-Za-z0-9+/=]{11}\\s*$".toRegex()

        /**
         * An SMS Retriever message ("<#> 1496 is your JioHotstar verification code. /wAX/OsgZgE") starts with "<#>" and ends with the
         * app's 11-character hash, which is not part of the text and could hold a run of digits. It is cut off before the code is read.
         */
        fun withoutRetrieverHash(body: String): String =
            if (body.trimStart().startsWith("<#>")) body.replace(RETRIEVER_HASH, "") else body

        private val OTP_KEYWORDS = listOf(
            "One-Time Password", "one time password", "Verification Code",
            "Delivery Code", "Happy Code", "Share Code", "OTP"
        )

        /**
         * A one-time-password message names the thing AND carries the code. A warning such as "will never call you for
         * the card number/CVV/OTP/PIN" mentions OTP but holds no code, so it is not one (and offers nothing to copy).
         */
        fun isOtp(rawBody: String): Boolean {
            val body = withoutRetrieverHash(rawBody)
            if (OTP_KEYWORDS.none { body.contains(it, ignoreCase = true) }) return false
            val code = if (body.contains("Simpl", ignoreCase = true)) SIMPL_CODE else NUMERIC_CODE
            return code.containsMatchIn(body)
        }

        /**
         * "Your OTP for txn of Rs 1500 at Amazon is 482913": the first run of digits is the amount, not the code.
         * Numbers that read as money, a card/account tail or a reference are set aside; of the rest, one that follows
         * "is", ":" or "-" is the code, else the first.
         */
        private fun likelyCode(body: String): String? {
            val candidates = DIGITS.findAll(body).filter { m ->
                val before = body.substring(maxOf(0, m.range.first - 14), m.range.first)
                val after = body.substring(m.range.last + 1, minOf(body.length, m.range.last + 4))
                !NOT_A_CODE_BEFORE.containsMatchIn(before) && !after.startsWith(".0") && !after.startsWith(".1") && !after.startsWith(".5")
            }.toList()
            val stated = candidates.firstOrNull { m ->
                STATES_CODE.containsMatchIn(body.substring(maxOf(0, m.range.first - 4), m.range.first))
            }
            return (stated ?: candidates.firstOrNull())?.groupValues?.get(1)
        }

        private val DIGITS = "(?<!\\d)(\\d{4,8})(?!\\d)".toRegex()
        private val NOT_A_CODE_BEFORE = "(?i)(?:rs\\.?|inr\\.?|₹|amount|amt|ending|ref(?:erence)?\\.?|a/c|acct?|card|no\\.?|id)\\s*[:.#-]?\\s*$".toRegex()
        private val STATES_CODE = "(?:\\bis|[:-])\\s*$".toRegex()

        private fun plainAmount(raw: String): String? {
            val clean = raw.replace(",", "").trimEnd('.')
            return (if (clean.endsWith(".00")) clean.dropLast(3) else clean).takeIf { it.isNotEmpty() }
        }

        private val STATED_CODE = "(?i)\\bOTP\\s*(?:is|:|-)\\s*(\\d{4,8})(?!\\d)".toRegex()
        private val TRANSFER_AMOUNT = "(?i)\\bfor\\s+(?:Rs\\.?|INR\\.?)\\s?([0-9][0-9,]*(?:\\.\\d{1,2})?)\\s+(?:TXN|transaction|transfer|payment)\\b".toRegex()

        private val SIMPL_CODE = "\\b([A-Z0-9]{10})\\b".toRegex()
        private val NUMERIC_CODE = "(?:[^\\d]|^)(\\d{4,8})(?:[^\\d]|$)".toRegex()

        // Bank name, short codes, or even phone numbers
        private val SENDER =
            "(?:[A-Za-z]+(?: Bank)?|\\b[A-Z]{3,}\\b|\\d{10})".toRegex(RegexOption.IGNORE_CASE)
        private val AMOUNT = Regex("INR ([0-9,]+)\\.00")
        private val CARD_NUMBER = Regex("ending (\\d+)")
    }
}
