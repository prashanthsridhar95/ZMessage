package com.zyke.message.domain.classification

/**
 * Scores how much a message looks like a scam or unsolicited pitch, from the way such messages are written:
 * short links, promised returns, urgency, messaging-app links. Anything that reads like a real transaction
 * is protected. All local; no list to download.
 */
object SpamScorer {
    private const val THRESHOLD = 3

    private val SHORT_LINK = Regex("(?i)\\b(?:tinyurl\\.com|bit\\.ly|shorturl\\.at|cutt\\.ly|t\\.ly|rb\\.gy|is\\.gd|compact\\.link|wa\\.me|api\\.whatsapp\\.com|t\\.me)/")
    private val PROMISED_RETURNS = Regex("(?i)\\b(?:profit|returns?|jackpot|lottery|winner|guaranteed|earn(?:ings)?|income|bonus|trade model|trades?)\\b")
    private val URGENCY = Regex("(?i)\\b(?:last (?:entries|chance|date)|act now|hurry|expires? (?:today|soon)|limited (?:slots|seats|offer)|only \\d+ left|within \\d+ hours?)\\b")
    private val LOAN_PITCH = Regex("(?i)\\b(?:instant|personal|pre-?approved)? ?loan\\b.*\\b(?:approved|almost complete|eligible|apply)\\b|\\bview next steps\\b")
    private val WORK_FROM_HOME = Regex("(?i)work from home|part[- ]time job|daily income")
    private val PLAIN_NUMBER_SENDER = Regex("^\\+?\\d{10,13}$")

    /** Real transactions and service messages: these pull the score down. */
    private val GENUINE = Regex(
        "(?i)\\b(?:debited|credited|a/c|acct|txn|upi|otp|one[- ]time|statement|avbl|available balance|ref(?:erence)? ?no|card ending|xx\\d{2,4})\\b"
    )

    fun score(sender: String, body: String): Int {
        var score = 0
        if (SHORT_LINK.containsMatchIn(body)) score += 2
        if (PROMISED_RETURNS.containsMatchIn(body) && Regex("(?i)(?:rs\\.?|inr|₹)\\s?[\\d,]+").containsMatchIn(body)) score += 2
        if (URGENCY.containsMatchIn(body)) score += 1
        if (LOAN_PITCH.containsMatchIn(body)) score += 2
        if (WORK_FROM_HOME.containsMatchIn(body)) score += 2
        if (PLAIN_NUMBER_SENDER.matches(sender.trim()) && "http" in body.lowercase()) score += 1
        if (GENUINE.containsMatchIn(body)) score -= 3
        return score
    }

    fun isSpam(sender: String, body: String): Boolean = score(sender, body) >= THRESHOLD
}
