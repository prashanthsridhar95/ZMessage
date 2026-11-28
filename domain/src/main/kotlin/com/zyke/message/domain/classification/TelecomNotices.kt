package com.zyke.message.domain.classification

import com.zyke.message.domain.life.LifeFacts
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.life.LifeParser
import com.zyke.message.domain.model.NetworkCarrier
import java.time.LocalDate

/**
 * Plan messages from telecom operators that the money rules should never see: a Jio plan that ends (in Tamil or English),
 * the data-used alert, the "thanks for calling, get a pack" offer, and the regulator's SIM advisory. The Tamil checks are
 * plain substring tests on keywords: no word-boundary patterns, which do not mean anything in Tamil script.
 */
object TelecomNotices {
    sealed interface Notice {
        /** A plan that expired, is about to, or was already renewed. */
        data class Plan(val facts: LifeFacts) : Notice
        /** A data-usage alert: [percent] is null when the text only says how much is left or that the quota is finished. */
        data class DataUsed(
            val carrier: NetworkCarrier,
            val percent: Int?,
            val left: String?,
            val expires: LocalDate?,
            val exhausted: Boolean,
            val daily: Boolean
        ) : Notice
        data object Offer : Notice
        data object Advisory : Notice
    }

    // Tamil keywords: recharge, plan, ends, "has ended", "is ending", already, "for calling", "get (it)"
    private const val RECHARGE = "ரீசார்ஜ்"
    private const val PLAN = "திட்டம்"
    private const val ENDS = "முடிவடை"
    private const val HAS_ENDED = "முடிவடைந்துவிட்டது"
    private const val ENDING = "முடிவடைகிறது"
    private const val ALREADY = "ஏற்கனவே"
    private const val FOR_CALLING = "அழைத்ததற்கு"
    private const val GET_IT = "பெறுங்கள்"
    private const val JIO_NUMBER = "Jio எண்"

    private val STAMP = Regex("(\\d{1,2}-[A-Za-z]{3}-\\d{2,4})\\s+(\\d{1,2}:\\d{2})")
    private val PLAN_CODE = Regex("Rs\\d+_[A-Za-z0-9_./]{2,40}")
    private val PERCENT_AFTER_VERB = Regex(
        "(?i)\\b(?:consumed|used|utili[sz]ed|exhausted)\\s+(\\d{1,3})%\\s+of\\s+(?:your\\s+|the\\s+)?(?:daily\\s+)?(?:high[\\s-]?speed\\s+)?(?:\\w+\\s+)?data"
    )
    private val PERCENT_BEFORE_VERB = Regex(
        "(?i)\\b(\\d{1,3})%\\s+of\\s+(?:your\\s+|the\\s+)?(?:daily\\s+|available\\s+)?(?:\\w+\\s+)?data\\s+(?:is\\s+|has\\s+been\\s+)?(?:used|consumed|utili[sz]ed|exhausted)"
    )
    private val LEFT_AFTER = Regex(
        "(?i)(\\d+(?:\\.\\d+)?)\\s?(GB|MB)\\s+(?:of\\s+)?(?:data\\s+)?(?:is\\s+|are\\s+)?(?:left|remaining)\\b"
    )
    private val LEFT_LABELLED = Regex("(?i)\\b(?:balance|remaining|left)\\s+data(?:\\s+quota)?\\s*[:\\-]?\\s*(\\d+(?:\\.\\d+)?)\\s?(GB|MB)")
    private val EXHAUSTED = Regex(
        "(?i)\\b(?:(?:data\\s+)?(?:quota|limit|pack|balance)|high[\\s-]?speed data|daily data(?: limit)?)\\s+(?:is\\s+|has\\s+been\\s+|have\\s+been\\s+|got\\s+)?" +
            "(?:exhausted|finished|over|used up|consumed)\\b|\\b(?:exhausted|finished|used up|consumed)\\s+(?:all\\s+)?(?:of\\s+)?your\\s+(?:daily\\s+)?(?:high[\\s-]?speed\\s+)?data|\\bno data (?:left|remaining)\\b"
    )
    private val EXPIRY = Regex(
        "(?i)\\b(?:expires?(?:\\s+on)?|expiring(?:\\s+on)?|expiry(?:\\s+date)?|ends?\\s+on|valid\\s+(?:till|until|upto|up\\s+to)|" +
            "validity\\s+(?:till|until|ends?(?:\\s+on)?|expires?(?:\\s+on)?))\\b[:\\s-]*(.{0,24})"
    )
    private val DATA_WORD = Regex("(?i)\\bdata\\b")
    private val DAILY = Regex("(?i)\\bdaily\\b")
    private val VI_WORD = Regex("\\bVi\\b|(?i:vodafone|myvi)")
    private val SENDER_VI = Regex("^(?:VI|VIINFO|VIHELP|VICARE)$")

    /** A real payment message that happens to mention data (a recharge debit): left to the money rules. */
    private val MONEY_MOVED = Regex(
        "(?i)(?:(?:rs\\.?|inr|₹)\\s*\\d[^\\n]*\\b(?:debited|paid|credited|received|deducted)\\b|\\b(?:debited|paid|credited|deducted)\\b[^\\n]*(?:rs\\.?|inr|₹)\\s*\\d)"
    )

    /** A telecom operator's own web address, written without "https://" as these texts do (i.airtel.in/Prepaidservice). */
    private val OPERATOR_LINK = Regex("(?i)\\b((?:[a-z0-9-]+\\.)*(?:airtel\\.in|jio\\.com|myvi\\.in|vi\\.in|bsnl\\.co\\.in)(?:/[^\\s,;)]*)?)")
    private val ADVISORY = Regex("(?i)^Advisory as per the Telecommunications Act")

    /** The operator's top-up link in [body] with a scheme ("https://i.airtel.in/Prepaidservice"), or null. Only the operators' own domains. */
    fun topUpLink(body: String): String? {
        val found = OPERATOR_LINK.find(com.zyke.message.domain.util.BodyMemo.bounded(body)) ?: return null
        val link = found.groupValues[1].trimEnd('.', ':', '!', '?')
        return if (link.contains("://")) link else "https://$link"
    }

    /** [sender] is the raw header; it names the operator when the text does not. */
    fun detect(rawBody: String, received: LocalDate, sender: String = ""): Notice? {
        val body = com.zyke.message.domain.util.BodyMemo.bounded(rawBody)
        if (ADVISORY.containsMatchIn(body.trimStart())) return Notice.Advisory
        dataUsed(body, received, sender)?.let { return it }
        val jio = body.contains("Jio", ignoreCase = true)
        if (!jio) return null

        val isPlanNote = body.contains(PLAN) && body.contains(ENDS) && (body.contains(RECHARGE) || body.contains(JIO_NUMBER))
        if (isPlanNote) return Notice.Plan(plan(body, received))
        if (body.contains(FOR_CALLING) || body.contains(GET_IT)) return Notice.Offer
        return null
    }

    private fun carrierOf(body: String, sender: String): NetworkCarrier? {
        val tokens = sender.uppercase().split('-').filter { it.isNotEmpty() }
        return when {
            body.contains("airtel", ignoreCase = true) -> NetworkCarrier.AIRTEL
            body.contains("jio", ignoreCase = true) -> NetworkCarrier.JIO
            body.contains("bsnl", ignoreCase = true) -> NetworkCarrier.BSNL
            VI_WORD.containsMatchIn(body) -> NetworkCarrier.VI
            tokens.any { it.contains("AIRTEL") } -> NetworkCarrier.AIRTEL
            tokens.any { it.contains("JIO") } -> NetworkCarrier.JIO
            tokens.any { it.contains("BSNL") } -> NetworkCarrier.BSNL
            tokens.any { SENDER_VI.matches(it) || it.contains("VODAFONE") } -> NetworkCarrier.VI
            else -> null
        }
    }

    /** "You have consumed 90% of your data", "only 1 GB left", "daily data quota exhausted": a usage alert, never money. */
    private fun dataUsed(body: String, received: LocalDate, sender: String): Notice.DataUsed? {
        if (!DATA_WORD.containsMatchIn(body) || MONEY_MOVED.containsMatchIn(body)) return null
        val carrier = carrierOf(body, sender) ?: return null
        val percent = (PERCENT_AFTER_VERB.find(body) ?: PERCENT_BEFORE_VERB.find(body))?.groupValues?.get(1)?.toInt()?.coerceIn(0, 100)
        val leftMatch = LEFT_AFTER.find(body) ?: LEFT_LABELLED.find(body)
        val left = leftMatch?.let { "${it.groupValues[1]} ${it.groupValues[2].uppercase()}" }
        val exhausted = EXHAUSTED.containsMatchIn(body)
        if (percent == null && left == null && !exhausted) return null
        val expires = EXPIRY.find(body)?.let { LifeParser.dateIn(it.groupValues[1], received) }
        return Notice.DataUsed(carrier, percent ?: if (exhausted) 100 else null, left, expires, exhausted, DAILY.containsMatchIn(body))
    }

    private fun plan(body: String, received: LocalDate): LifeFacts {
        val stamp = STAMP.find(body)
        val date = stamp?.let { LifeParser.dateIn(it.groupValues[1], received) }
        val time = stamp?.let { LifeParser.timeIn(it.groupValues[2]) }
        val (status, done) = when {
            body.contains(ALREADY) && body.contains(RECHARGE) && !body.contains(ENDING) -> "Plan renewed" to true
            body.contains(HAS_ENDED) -> "Plan expired" to true
            else -> "Plan expires" to false
        }
        return LifeFacts(LifeKind.RENEWAL, status, date, time, PLAN_CODE.find(body)?.value, isDone = done, merchant = "Jio")
    }
}
