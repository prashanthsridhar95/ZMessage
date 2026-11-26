package com.zyke.message.domain.ai

/** What asking the model to phrase something came to. */
sealed interface Phrased {
    data class Ok(val text: String) : Phrased

    /** The model answered but the answer did not pass its checks. Nothing is shown from it. */
    data class Rejected(val reason: String) : Phrased

    data class Failed(val kind: AiFailure) : Phrased
}

/**
 * The shared way to get one checked piece of text out of the model: look in the cache (re-checking what is found), else ask through
 * the single-flight gateway, check the answer with [accept], and keep it only if it passed. [accept] is the whole safety story
 * of a feature, so it is always supplied by the feature's own guard and must give the same result when run again on its own output.
 */
class AiPhraser(private val gateway: AiGateway, private val cache: AiCache) {
    /** The checked text for [key] if the cache holds an answer that still passes [accept]; never calls the model. */
    fun known(key: String, accept: (String) -> String?): String? = cache.get(key)?.let(accept)

    suspend fun phrase(key: String, prompt: String, maxTokens: Int, accept: (String) -> String?): Phrased =
        phraseWith(key, accept) { gateway.run(prompt, maxTokens = maxTokens) }

    /** For callers that must bound the wait (background preparation): the request gets [timeoutMs] and no retry. */
    suspend fun phraseQuickly(key: String, prompt: String, maxTokens: Int, timeoutMs: Long, accept: (String) -> String?): Phrased =
        phraseWith(key, accept) { gateway.run(prompt, maxTokens = maxTokens, timeoutMs = timeoutMs, retries = 0) }

    private suspend fun phraseWith(key: String, accept: (String) -> String?, ask: suspend () -> AiResult): Phrased {
        known(key, accept)?.let { return Phrased.Ok(it) }
        return when (val r = ask()) {
            is AiResult.Failure -> Phrased.Failed(r.kind)
            is AiResult.Text -> {
                val ok = accept(r.text) ?: return Phrased.Rejected("The answer did not match the facts")
                cache.put(key, r.text.take(MAX_STORED))
                Phrased.Ok(ok)
            }
        }
    }

    fun forget(key: String) = cache.remove(key)

    private companion object { const val MAX_STORED = 600 }
}

/**
 * The checks every sentence the model writes must pass before it is shown, whatever the feature. The feature supplies the texts
 * the code gave the model ([allowed]): every figure in the sentence must be in one of them, every name must be written in one of
 * them, and the sentence must be short, plain and free of markup, emoji and spelled-out numbers (which the number check cannot see).
 */
object AiSentence {
    private const val SYMBOL_FROM = 0x2190
    private const val SYMBOL_TO = 0x2BFF
    private val MARKUP = setOf('*', '#', '<', '>', '`', '_', '{', '}', '[', ']')
    private val LEAD = Regex("(?i)^(answer|summary|sentence|explanation)\\s*:\\s*")
    private val SENTENCE_END = Regex("[.!?](\\s|$)")
    private val NUMBER_WORDS = Regex(
        "(?i)\\b(two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|" +
            "twenty|thirty|forty|fifty|hundred|thousand|lakh|lakhs|crore|crores|million|billion|dozen|double|triple|twice|thrice|half)\\b"
    )
    private val CAPITALISED = Regex("\\b[A-Z][a-zA-Z]{2,}\\b")
    private val RUPEE_WORD = Regex("(?i)\\brs\\.?(?=\\s*\\d)")

    /**
     * The cleaned sentence, or null. [allowed] are the code's own texts (facts lines, or the message body).
     * @param maxSentences how many full stops, question marks and exclamation marks at most
     */
    fun verify(reply: String, allowed: Collection<String>, maxChars: Int, maxSentences: Int): String? {
        val text = reply.trim().replace(Regex("\\s+"), " ").trim('"', '“', '”').replace(LEAD, "").trim()
        if (text.length < MIN_CHARS || text.length > maxChars) return null
        if (text.any { it.isSurrogate() || it.code in SYMBOL_FROM..SYMBOL_TO || it in MARKUP }) return null
        // "Rs. 250" is one figure, not the end of a sentence.
        if (SENTENCE_END.findAll(text.replace(RUPEE_WORD, "Rs")).count() > maxSentences) return null
        if (text.count { it.isLetter() } < MIN_LETTERS) return null
        if (!NumberGuard.onlyAllowed(text, allowed)) return null
        if (NUMBER_WORDS.containsMatchIn(text)) return null
        if (!namesAreSupplied(text, allowed)) return null
        return text
    }

    /** A capitalised word in the middle of a sentence (a payee, a category) must be one of the words the code supplied. */
    private fun namesAreSupplied(text: String, allowed: Collection<String>): Boolean {
        val supplied = allowed.flatMap { Regex("[A-Za-z]+").findAll(it).map { m -> m.value.lowercase() }.toList() }.toSet()
        val starts = HashSet<Int>().apply {
            add(0)
            SENTENCE_END.findAll(text).forEach { add(it.range.last + 1) }
        }
        return CAPITALISED.findAll(text).all { m ->
            m.range.first in starts || m.value.lowercase() in supplied || m.value == "Rs" || m.value == "INR"
        }
    }

    private const val MIN_CHARS = 8
    private const val MIN_LETTERS = 6
}
