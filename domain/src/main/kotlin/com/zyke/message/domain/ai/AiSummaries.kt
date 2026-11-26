package com.zyke.message.domain.ai

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.triage.TriageCard
import com.zyke.message.domain.triage.TriageDeck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Checks a one-line summary the model wrote. It is shown only if it passes; otherwise the rule-based line stays. */
object AiSummaryGuard {
    const val MAX_CHARS = 90
    private const val MIN_LETTERS = 6

    /** The cleaned summary, or null. Every number in it must be written in the message. */
    fun accept(reply: String, body: String): String? {
        var line = reply.trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        line = line.removePrefix("Summary:").removePrefix("summary:").trim().trim('"', '\'', '“', '”', '-', '*', '•').trim()
        if (line.length > MAX_CHARS || line.count { it.isLetter() } < MIN_LETTERS) return null
        if (line.any { it.isSurrogate() || it.code in SYMBOL_FROM..SYMBOL_TO } || '<' in line || '>' in line) return null
        if (line.contains("http", ignoreCase = true) && !body.contains("http", ignoreCase = true)) return null
        if (!NumberGuard.onlyAllowed(line, listOf(body))) return null
        return line
    }

    private const val SYMBOL_FROM = 0x2190
    private const val SYMBOL_TO = 0x2BFF
}

/** A message whose rule-based summary is generic, and the text to rewrite. */
data class SummaryCandidate(val messageId: Long, val key: String, val body: String)

/** Where Home asks for better summaries and reads the ones that are ready. */
interface AiSummarySource {
    /** Message id to its checked summary. Empty while the feature is off. */
    val summaries: StateFlow<Map<Long, String>>

    /** Asks for summaries of [candidates] (newest first). Returns at once; answers arrive in [summaries]. */
    fun request(candidates: List<SummaryCandidate>)
}

object NoAiSummaries : AiSummarySource {
    override val summaries: StateFlow<Map<Long, String>> = MutableStateFlow(emptyMap())
    override fun request(candidates: List<SummaryCandidate>) = Unit
}

object AiSummaryOverlay {
    /** The most cards asked about at once; the rest wait for the next rebuild. */
    const val MAX_REQUESTS = 6

    /** The deck with each card's headline replaced by its AI summary where there is one. The same instance when none applies. */
    fun apply(deck: TriageDeck, summaries: Map<Long, String>): TriageDeck {
        if (summaries.isEmpty()) return deck
        var changed = false
        fun fix(card: TriageCard): TriageCard {
            val s = summaries[card.messageId] ?: return card
            changed = true
            return card.copy(headline = s)
        }
        val stacks = deck.stacks.map { st -> st.copy(items = st.items.map(::fix), autoCleared = st.autoCleared.map(::fix)) }
        return if (changed) deck.copy(stacks = stacks, autoCleared = deck.autoCleared.map(::fix)) else deck
    }

    /** The newest cards whose summary is only the start of the text (a message nobody has read into a payment, bill or code). */
    fun candidates(deck: TriageDeck, byId: Map<Long, ClassifiedMessage>, have: Set<Long>): List<SummaryCandidate> =
        deck.stacks.asSequence().flatMap { it.items.asSequence() }
            .sortedByDescending { it.dateMillis }
            .mapNotNull { card ->
                val m = byId[card.messageId] ?: return@mapNotNull null
                val generic = m.content is MessageContent.Generic || m.content is MessageContent.Notice
                if (!generic || card.messageId in have || m.message.body.length < MIN_BODY) null
                else SummaryCandidate(m.id, AiKeys.of("sum", m.message.sender + "|" + m.message.body), m.message.body)
            }
            .take(MAX_REQUESTS).toList()

    private const val MIN_BODY = 40
}
