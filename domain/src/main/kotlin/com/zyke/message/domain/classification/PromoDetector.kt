package com.zyke.message.domain.classification

/** Spots marketing messages so they can be kept out of the main feed. */
object PromoDetector {
    // Indian DLT sender headers look like "AD-BRANDX-P"; the suffix is the traffic type:
    // P = promotional, S = service, T = transactional, G = government.
    private val PROMOTIONAL_HEADER = Regex("^[A-Za-z0-9]{2}-.+-P$", RegexOption.IGNORE_CASE)

    private val HINTS = listOf(
        "T&C", "unsubscribe", "STOP to", "% off", "use code", "limited time",
        "exclusive offer", "click here", "flat rs", "cashback up to",
        // Scam-style: a WhatsApp deep link or a work-from-home pitch
        "api.whatsapp.com/send", "work from home",
        // Trading-tip and instant-loan scams
        "Airtel Warning: SPAM", "profit in", "Trade model", "View next steps", "Loan is almost complete"
    )

    fun isPromotional(sender: String, body: String): Boolean =
        PROMOTIONAL_HEADER.matches(sender.trim()) || hasPromoHints(body) || SpamScorer.isSpam(sender, body)

    fun hasPromoHints(body: String): Boolean = HINTS.any { body.contains(it, ignoreCase = true) }
}
