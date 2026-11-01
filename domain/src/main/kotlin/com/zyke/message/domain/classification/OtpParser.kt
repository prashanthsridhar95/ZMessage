package com.zyke.message.domain.classification

/** Result of pulling the interesting bits out of a one-time-password SMS. */
data class ParsedOtp(
    val code: String,
    val senderName: String,
    val paymentAmount: String?,
    val cardNumber: String?
)

class OtpParser {

    fun parse(body: String, sender: String): ParsedOtp {
        val codeRegex = if (body.contains("Simpl", ignoreCase = true)) SIMPL_CODE else NUMERIC_CODE
        val code = codeRegex.find(body)?.groupValues?.get(1).orEmpty()

        val senderName = if (body.contains("Bank")) {
            SENDER.find(body)?.value?.trim() ?: sender
        } else {
            sender
        }

        val cardNumber = CARD_NUMBER.find(body)?.groupValues?.get(1)
        val amount = AMOUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.trim()
        val hasPayment = cardNumber != null && amount != null

        return ParsedOtp(
            code = code,
            senderName = senderName,
            paymentAmount = amount.takeIf { hasPayment },
            cardNumber = cardNumber.takeIf { hasPayment }
        )
    }

    companion object {
        private val OTP_KEYWORDS = listOf(
            "One-Time Password", "one time password", "Verification Code",
            "Delivery Code", "Happy Code", "Share Code", "OTP"
        )

        /**
         * A one-time-password message names the thing AND carries the code. A warning such as "will never call you for
         * the card number/CVV/OTP/PIN" mentions OTP but holds no code, so it is not one (and offers nothing to copy).
         */
        fun isOtp(body: String): Boolean {
            if (OTP_KEYWORDS.none { body.contains(it, ignoreCase = true) }) return false
            val code = if (body.contains("Simpl", ignoreCase = true)) SIMPL_CODE else NUMERIC_CODE
            return code.containsMatchIn(body)
        }

        private val SIMPL_CODE = "\\b([A-Z0-9]{10})\\b".toRegex()
        private val NUMERIC_CODE = "(?:[^\\d]|^)(\\d{4,7})(?:[^\\d]|$)".toRegex()

        // Bank name, short codes, or even phone numbers
        private val SENDER =
            "(?:[A-Za-z]+(?: Bank)?|\\b[A-Z]{3,}\\b|\\d{10})".toRegex(RegexOption.IGNORE_CASE)
        private val AMOUNT = Regex("INR ([0-9,]+)\\.00")
        private val CARD_NUMBER = Regex("ending (\\d+)")
    }
}
