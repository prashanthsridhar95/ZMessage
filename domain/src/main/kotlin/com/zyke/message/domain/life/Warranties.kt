package com.zyke.message.domain.life

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What kind of promise the shop made. */
enum class WarrantyKind(val label: String) {
    WARRANTY("Warranty"),
    RETURN("Return window")
}

/** The day a warranty, guarantee or return / replacement window ends, read from a purchase message. */
data class Warranty(val kind: WarrantyKind, val message: ClassifiedMessage, val endsOn: LocalDate, val explicit: Boolean) {
    /** "Return window for Popeye order", "Warranty for Croma purchase". */
    val title: String
        get() {
            val life = message.content as? MessageContent.Life
            val subject = life?.title?.takeIf { it.isNotBlank() } ?: message.merchant.name
            return "${kind.label} for $subject" + if (life != null) " order" else ""
        }
}

/**
 * Reads warranty, guarantee and return / replacement windows from order and purchase messages. A date written out
 * ("warranty till 12 Oct 2027") is used as stated; a length ("1 year warranty", "10-day return") is counted from the day
 * the message arrived. Anything vaguer ("easy returns", "T&C apply") is left alone.
 */
object Warranties {
    private val CUE = Regex("(?i)\\b(warranty|guarantee|return window|returnable|returns?|replace(?:ment)?|exchange)\\b")
    private val PURCHASE = Regex("(?i)\\b(order|purchase|purchased|bought|invoice|delivered|shipment|item|product|placed|activated|registered)\\b")
    private val NOISE = Regex("(?i)\\b(refund|credited|cashback|otp|deliver(?:y|ed)? (?:in|within)|arrive|reversal)\\b")
    private val DATE_AFTER = Regex(
        "(?i)\\b(warranty|guarantee|return window|returnable|returns?|replace(?:ment)?|exchange)\\b[^.\\n]{0,40}?" +
            "\\b(?:till|until|upto|up to|by|before|expires? on|ends? on|valid till|valid until|through)\\b[:\\s]*(.{0,24})"
    )
    private val DURATION = Regex("(?i)(?<![\\d-])(\\d{1,3}|one|two|three|four|five|six|seven|ten)[- ]?(days?|weeks?|months?|years?|yrs?)\\b")
    private val WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "ten" to 10)
    private const val NEAR = 40
    private const val NOISE_REACH = 30
    private const val MAX_YEARS = 10L

    fun of(message: ClassifiedMessage, zone: ZoneId): Warranty? {
        if (message.isPromotional || message.isMuted) return null
        when (val content = message.content) {
            is MessageContent.Otp, is MessageContent.CardBill, is MessageContent.ScheduledPayment -> return null
            is MessageContent.Life -> if (content.kind != LifeKind.DELIVERY) return null
            is MessageContent.Transaction -> if (content.isReceived) return null
            else -> Unit
        }
        val text = message.message.body.replace(Regex("\\s+"), " ").trim()
        if (!CUE.containsMatchIn(text)) return null
        val received = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
        return explicit(message, text, received) ?: counted(message, text, received)
    }

    /** Every warranty in [messages], one per kind and day and subject, soonest first. */
    fun all(messages: List<ClassifiedMessage>, zone: ZoneId): List<Warranty> =
        messages.mapNotNull { of(it, zone) }
            .distinctBy { Triple(it.kind, it.endsOn, it.title.lowercase()) }
            .sortedBy { it.endsOn }

    private fun kindOf(cue: String) = if (cue.lowercase().let { it == "warranty" || it == "guarantee" }) WarrantyKind.WARRANTY else WarrantyKind.RETURN

    private fun explicit(message: ClassifiedMessage, text: String, received: LocalDate): Warranty? {
        for (match in DATE_AFTER.findAll(text)) {
            val date = LifeParser.dateIn(match.groupValues[2], received) ?: continue
            if (date.isBefore(received) || date.isAfter(received.plusYears(MAX_YEARS))) continue
            return Warranty(kindOf(match.groupValues[1]), message, date, explicit = true)
        }
        return null
    }

    private fun counted(message: ClassifiedMessage, text: String, received: LocalDate): Warranty? {
        val content = message.content
        if (content !is MessageContent.Life && content !is MessageContent.Transaction && !PURCHASE.containsMatchIn(text)) return null
        var best: Triple<Int, WarrantyKind, LocalDate>? = null
        for (cue in CUE.findAll(text)) for (duration in DURATION.findAll(text)) {
            val gap = distance(cue.range, duration.range)
            if (gap > NEAR || (best != null && gap >= best.first)) continue
            val between = text.substring((minOf(cue.range.first, duration.range.first) - NOISE_REACH).coerceAtLeast(0), maxOf(cue.range.last, duration.range.last) + 1)
            if (NOISE.containsMatchIn(between)) continue
            val n = duration.groupValues[1].let { it.toIntOrNull() ?: WORDS[it.lowercase()] } ?: continue
            val end = end(received, n.toLong(), duration.groupValues[2].lowercase().first()) ?: continue
            best = Triple(gap, kindOf(cue.groupValues[1]), end)
        }
        return best?.let { Warranty(it.second, message, it.third, explicit = false) }
    }

    private fun distance(a: IntRange, b: IntRange) = if (a.last < b.first) b.first - a.last else maxOf(0, a.first - b.last)

    private fun end(from: LocalDate, n: Long, unit: Char): LocalDate? {
        if (n <= 0) return null
        val end = when (unit) {
            'd' -> from.plusDays(n)
            'w' -> from.plusWeeks(n)
            'm' -> from.plusMonths(n)
            else -> from.plusYears(n)
        }
        return end.takeIf { !it.isAfter(from.plusYears(MAX_YEARS)) }
    }
}
