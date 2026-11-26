package com.zyke.message.domain.ai

import com.zyke.message.domain.insights.DigestFormat
import com.zyke.message.domain.insights.DigestKind
import com.zyke.message.domain.insights.DigestSummary

/** The figures behind the morning digest's opening line, all from the digest the app already computed (never from the model). */
data class DigestFacts(val lines: List<String>) {
    /** The same facts give the same line, so the notification finds what the app prepared earlier by looking this key up. */
    val key: String get() = AiKeys.of("digest", lines.joinToString("|"))
}

/**
 * The morning digest's one-line opening, prepared while the app is open and only ever read (never asked for) when the digest is
 * posted. The facts leave out yesterday's spending, which is not known the evening before; the digest is rebuilt for the
 * morning ahead, so what is due and what is on are the same facts the notification will compute.
 */
object AiDigest {
    const val MAX_CHARS = 150
    private const val MAX_SENTENCES = 2
    private const val MIN_ITEMS = 2
    private const val MAX_ITEM_LINES = 3

    /** Null when there is too little in the digest for an opening line to add anything. */
    fun facts(summary: DigestSummary?): DigestFacts? {
        val items = summary?.items?.filter { it.kind != DigestKind.SPENT }.orEmpty()
        if (items.size < MIN_ITEMS) return null
        val lines = buildList {
            add("Things on the list: ${items.size}")
            val urgent = items.count { it.urgent }
            if (urgent > 0) add("Due or on today: $urgent")
            val dues = items.filter { it.kind == DigestKind.BILL || it.kind == DigestKind.DEBIT }
            if (dues.isNotEmpty()) {
                val total = dues.mapNotNull { it.amount }.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                add("Bills and debits coming: ${dues.size}" + if (total.signum() > 0) ", ${DigestFormat.rupees(total)} in all" else "")
            }
            items.take(MAX_ITEM_LINES).forEach { add(AiPrompts.clean(it.text, MAX_LINE)) }
        }
        return DigestFacts(lines)
    }

    /** The model's line if it passes every check, else null (the digest is then posted exactly as it always was). */
    fun verify(reply: String, facts: DigestFacts): String? = AiSentence.verify(reply, facts.lines, MAX_CHARS, MAX_SENTENCES)

    private const val MAX_LINE = 100
}

/** Prepares the opening line. Called only from a screen that is open (never from a receiver); the notification reads the cache. */
class AiDigestWriter(private val gateway: AiGateway, private val cache: AiCache) {
    private val phraser = AiPhraser(gateway, cache)

    fun known(facts: DigestFacts): String? = phraser.known(facts.key) { AiDigest.verify(it, facts) }

    /** Gives up quickly (no retries): the digest never waits for this, and the next time the app opens tries again. */
    suspend fun prepare(facts: DigestFacts): Phrased =
        phraser.phraseQuickly(facts.key, AiPrompts.digestLine(facts.lines), TOKENS, TIMEOUT_MS) { AiDigest.verify(it, facts) }

    private companion object {
        const val TOKENS = 70
        const val TIMEOUT_MS = 20_000L
    }
}
