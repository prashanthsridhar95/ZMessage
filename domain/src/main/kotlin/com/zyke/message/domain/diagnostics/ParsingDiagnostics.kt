package com.zyke.message.domain.diagnostics

import com.zyke.message.domain.classification.GenericTransactionParser
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

/** How well the parsers understand this inbox. */
data class ParsingDiagnostics(
    /** Messages that mention an amount and are not marketing. */
    val moneyMessages: Int,
    /** Of those, how many ended up as plain text. */
    val unrecognised: Int,
    /** Redacted copies of some unrecognised messages, safe-ish to paste into a bug report. */
    val samples: List<String>
) {
    val recognised: Int get() = moneyMessages - unrecognised
}

object DiagnosticsBuilder {
    fun build(messages: List<ClassifiedMessage>, maxSamples: Int = 40): ParsingDiagnostics {
        val money = messages.filter { !it.isPromotional && GenericTransactionParser.mentionsMoney(it.message.body) }
        val unrecognised = money.filter { it.content is MessageContent.Generic }

        return ParsingDiagnostics(
            moneyMessages = money.size,
            unrecognised = unrecognised.size,
            samples = unrecognised.map { MessageRedactor.redact(it.message.body) }.distinct().take(maxSamples)
        )
    }
}
