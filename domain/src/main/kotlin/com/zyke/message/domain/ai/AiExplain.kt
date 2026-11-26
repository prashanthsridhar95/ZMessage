package com.zyke.message.domain.ai

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

/** Checks the plain-words explanation of one message. Its figures and names must be the message's own; it may not point the reader anywhere. */
object AiExplainGuard {
    const val MAX_CHARS = 280
    private const val MAX_SENTENCES = 3
    private val DIRECTIVE = Regex("(?i)\\b(click|tap|visit|call|dial|share|forward|reply|install|download|log in|login|sign in|verify)\\b")

    fun accept(reply: String, body: String): String? {
        val text = AiSentence.verify(reply, listOf(body), MAX_CHARS, MAX_SENTENCES) ?: return null
        if (text.contains("http", ignoreCase = true) || text.contains("www.", ignoreCase = true)) return null
        if (DIRECTIVE.containsMatchIn(text)) return null
        return text
    }
}

/** "Explain a message": a short explanation for one message, on request. */
class AiExplainer(private val gateway: AiGateway, private val cache: AiCache) {
    private val phraser = AiPhraser(gateway, cache)

    private fun keyOf(sender: String, body: String) = AiKeys.of("explain", "$sender|$body")

    /** Whether a message may be explained at all: not a one-time code (the explanation could repeat it), and long enough to say something. */
    fun eligible(message: ClassifiedMessage): Boolean = message.content !is MessageContent.Otp && message.message.body.trim().length >= MIN_BODY

    fun known(sender: String, body: String): String? = phraser.known(keyOf(sender, body)) { AiExplainGuard.accept(it, body) }

    suspend fun explain(sender: String, body: String): Phrased =
        phraser.phrase(keyOf(sender, body), AiPrompts.explain(body), TOKENS) { AiExplainGuard.accept(it, body) }

    fun forget(sender: String, body: String) = phraser.forget(keyOf(sender, body))

    private companion object {
        const val MIN_BODY = 20
        const val TOKENS = 110
    }
}
