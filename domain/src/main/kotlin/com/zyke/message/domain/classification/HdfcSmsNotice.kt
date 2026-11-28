package com.zyke.message.domain.classification

/**
 * HDFC Bank's notice that it will stop UPI SMS alerts up to Rs.500 from 1 January 2027 and keep sending them by email. Spotted by its
 * words, in any sender, so the app can offer to read those emails. Nothing here is a payment and nothing is stored: the message is shown
 * as it always was, with a card under it in its details.
 */
object HdfcSmsNotice {
    private val STOPS = Regex("(?i)\\bstop\\s+UPI\\s+SMS\\s+alerts?\\b")
    private val UPI_SMS = Regex("(?i)\\bUPI\\s+SMS\\s+alerts?\\b")
    private val EMAIL_CONTINUES = Regex("(?i)\\bemail\\s+alerts?\\s+(?:will\\s+)?continue")
    private val HDFC = Regex("(?i)\\bHDFC\\b")

    fun matches(body: String): Boolean {
        val text = body.replace(Regex("\\s+"), " ")
        if (!HDFC.containsMatchIn(text)) return false
        return STOPS.containsMatchIn(text) || (UPI_SMS.containsMatchIn(text) && EMAIL_CONTINUES.containsMatchIn(text))
    }

    const val TITLE = "HDFC is stopping UPI SMS alerts"
    const val OFFER = "Small HDFC UPI payments will only arrive by email after 1 Jan 2027. Connect email alerts?"
}
