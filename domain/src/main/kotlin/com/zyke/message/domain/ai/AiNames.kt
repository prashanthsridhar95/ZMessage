package com.zyke.message.domain.ai

/**
 * Checks a payee name the model suggested for a messy one ("SWIGGY*ORDER 4455 BLR" -> "Swiggy"). The model may only tidy the name:
 * every word of its answer must be a word of the original (or the start of one, or one letter off), so it can drop codes and
 * add capitals but cannot invent a brand, a person or a number. The name is a suggestion for the name box; the person saves it.
 */
object AiNameGuard {
    const val MAX_NAME = 40
    private const val MIN_NAME = 2
    private const val MIN_PREFIX = 4

    private fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value.lowercase() }.toList()

    /** The suggested name, or null when it is not a safe tidy-up of [raw]. */
    fun accept(reply: String, raw: String): String? {
        val claimed = (MiniJson.firstObject(reply)?.text("name") ?: return null).replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
        if (claimed.length !in MIN_NAME..MAX_NAME || claimed.equals("null", true) || claimed.equals("none", true)) return null
        if (claimed.any { !(it.isLetterOrDigit() || it == ' ' || it == '&' || it == '\'' || it == '.' || it == '-') }) return null
        val mine = words(claimed)
        val theirs = words(raw)
        if (mine.isEmpty() || mine.none { w -> w.any { it.isLetter() } } || theirs.isEmpty()) return null
        if (!mine.all { w -> theirs.any { r -> sameWord(w, r) } }) return null
        // Nothing to suggest when the original already reads as this name.
        if (mine.joinToString("") == theirs.joinToString("")) return null
        return claimed
    }

    private fun sameWord(mine: String, theirs: String): Boolean =
        mine == theirs ||
            (mine.length >= MIN_PREFIX && theirs.startsWith(mine)) ||
            (mine.length >= MIN_PREFIX && theirs.length >= MIN_PREFIX && NearMatch.distance(mine, theirs) <= 1)
}

/** "Clean payee names": the model's tidy name for a messy payee, shown as a chip in the name box. Never saved by itself. */
class AiPayeeNamer(private val gateway: AiGateway, private val cache: AiCache) {
    private val phraser = AiPhraser(gateway, cache)

    private fun keyOf(raw: String) = AiKeys.of("name", raw.trim().lowercase())

    fun known(raw: String): String? = phraser.known(keyOf(raw)) { AiNameGuard.accept(it, raw) }

    suspend fun suggest(raw: String): Phrased {
        val name = raw.trim()
        if (name.length < MIN_RAW) return Phrased.Rejected("There is nothing to tidy")
        return phraser.phrase(keyOf(name), AiPrompts.payeeName(name), TOKENS) { AiNameGuard.accept(it, name) }
    }

    private companion object {
        const val MIN_RAW = 3
        const val TOKENS = 32
    }
}
