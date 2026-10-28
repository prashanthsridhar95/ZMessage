package com.zyke.message.domain.classification

/**
 * Reads a Google Pay notification that says UPI Lite was topped up. Small UPI Lite payments send no message of their
 * own, so the top-up is the money that leaves the bank, and it is counted as spent when it happens.
 */
object UpiLiteNotification {
    const val SENDER = "Google Pay"

    private val MENTIONS_LITE = Regex("(?i)\\bUPI\\s*Lite\\b")
    private val TOP_UP = Regex("(?i)\\b(?:refill(?:ed)?|re-?filled|top(?:ped)?[- ]?up|added|loaded|recharg(?:e|ed))\\b")
    private val FAILED = Regex("(?i)\\b(?:fail(?:ed|ure)?|declin(?:ed|e)|unsuccessful|could not|couldn't|pending|reversed|refund(?:ed)?)\\b")
    private val AMOUNT = Regex("(?i)(?:₹|Rs\\.?|INR)\\s*([\\d,]+(?:\\.\\d+)?)")

    /** The amount topped up, or null when the notification is about something else. */
    fun amount(title: String?, text: String?): String? {
        val flat = listOfNotNull(title, text).joinToString(" ").replace(Regex("[\\s\\u00a0]+"), " ")
        if (!MENTIONS_LITE.containsMatchIn(flat) || !TOP_UP.containsMatchIn(flat) || FAILED.containsMatchIn(flat)) return null
        return AMOUNT.find(flat)?.groupValues?.get(1)?.replace(",", "")?.trimEnd('.')?.takeIf { it.toBigDecimalOrNull()?.signum() == 1 }
    }

    /** The message the top-up is kept as, worded the way banks word it so it is read like any other top-up. */
    fun body(amount: String): String = "UPI LITE Top-up amounting to Rs.$amount has been successful. Via Google Pay."

    /** True for a message made by [body], so a bank's own SMS for the same top-up can replace it. */
    fun isFromNotification(sender: String): Boolean = sender == SENDER
}
