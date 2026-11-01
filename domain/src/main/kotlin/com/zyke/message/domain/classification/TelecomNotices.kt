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
        data class DataUsed(val carrier: NetworkCarrier, val percent: Int) : Notice
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
    private val DATA_ALERT = Regex("(?i)DATA Usage Alert!?\\s*(\\d{1,3})% of (?:your )?(?:available )?data used")
    private val ADVISORY = Regex("(?i)^Advisory as per the Telecommunications Act")

    fun detect(rawBody: String, received: LocalDate): Notice? {
        val body = com.zyke.message.domain.util.BodyMemo.bounded(rawBody)
        if (ADVISORY.containsMatchIn(body.trimStart())) return Notice.Advisory
        val jio = body.contains("Jio", ignoreCase = true)
        if (!jio) return null
        DATA_ALERT.find(body)?.let { return Notice.DataUsed(NetworkCarrier.JIO, it.groupValues[1].toInt().coerceIn(0, 100)) }

        val isPlanNote = body.contains(PLAN) && body.contains(ENDS) && (body.contains(RECHARGE) || body.contains(JIO_NUMBER))
        if (isPlanNote) return Notice.Plan(plan(body, received))
        if (body.contains(FOR_CALLING) || body.contains(GET_IT)) return Notice.Offer
        return null
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
