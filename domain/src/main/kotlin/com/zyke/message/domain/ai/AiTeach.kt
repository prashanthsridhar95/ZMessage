package com.zyke.message.domain.ai

import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.teach.LearnedTemplate
import com.zyke.message.domain.teach.TeachPicks
import com.zyke.message.domain.teach.TemplateLearner

/** A format ready to be saved as the person's own, and how much of the suggestion it could keep. */
data class AiTaught(val template: LearnedTemplate, val keptPayee: Boolean, val keptAccount: Boolean, val keptCategory: Boolean)

/**
 * Turns an accepted suggestion into the thing the app already knows how to save: a format the person taught ("Teach the app").
 * That is the same store, the same checks and the same Undo as teaching it by hand; the model's words are used only to point
 * at the amount, payee and account in the message, and the result must read back the very same message.
 */
object AiTeach {
    fun template(sms: SmsMessage, reading: CheckedReading): AiTaught? {
        val amountAt = reading.amountAt ?: return null
        if (!reading.kind.movesMoney) return null
        val offset = sms.body.length - sms.body.trimStart().length
        val tokens = TemplateLearner.tokenize(sms.body)
        fun indexAt(range: IntRange): Int = tokens.indexOfFirst { it.start <= range.first - offset && range.first - offset < it.end }
        val amount = indexAt(amountAt).takeIf { it >= 0 && tokens[it].isNumber } ?: return null
        val account = reading.last4At?.let { range ->
            tokens.indexOfFirst { it.start <= range.last - offset && range.last - offset < it.end }
                .takeIf { it >= 0 && tokens[it].text.all(Char::isDigit) && tokens[it].text.takeLast(LAST4) == reading.last4 && it != amount }
        }
        val payee = reading.merchantAt?.let { range ->
            val inside = tokens.indices.filter { tokens[it].start >= range.first - offset && tokens[it].end <= range.last + 1 - offset }
            if (inside.isEmpty() || amount in inside || account in inside) null else inside.first()..inside.last()
        }
        val received = reading.kind == ReadingKind.INCOME || reading.kind == ReadingKind.REFUND
        val category = reading.category?.builtIn
        // Most to least: a template that is too loose to trust is refused by the learner, so try with less.
        for ((p, a) in listOf(payee to account, payee to null, null to account, null to null)) {
            val template = TemplateLearner.learn(sms, TeachPicks(amount, p, a, received, category)) ?: continue
            return AiTaught(template, p != null, a != null, category != null)
        }
        return null
    }

    private const val LAST4 = 4
}
