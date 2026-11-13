package com.zyke.message.domain.categories

/**
 * Who a payment went to (or came from), as one stable key: the identity the person's rules for a payee are filed under.
 *
 * - A UPI id ("40-5@okhdfcbank", "Q123@ybl") is its own key, lower-case and otherwise untouched.
 * - A name loses its case, its punctuation and its spacing ("MR DIY", "Mr. Diy", "mr  diy " are one key), and the two
 *   company endings people spell differently are written one way ("Private Limited" / "Pvt. Ltd." / "PVT LTD" -> "pvt ltd").
 * - Two different people (or shops) are two keys; a person paid once by name and once by UPI id are two keys too: the
 *   app does not guess they are the same.
 *
 * Keys are lower-case so that they match the keys kept before this existed ([CategoryKey.normalise]); a name with no
 * punctuation to remove has the same key as before, and [CategoryKey.legacyOf] finds the older spelling of the rest.
 */
object PayeeKey {
    private val SPACES = Regex("\\s+")
    private val VPA = Regex("^[A-Za-z0-9._\\-]{2,}@[A-Za-z][A-Za-z0-9.]{1,}$")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** The key for [payee], or null when there is nothing to key on. */
    fun of(payee: String): String? {
        val trimmed = payee.trim().trim { !it.isLetterOrDigit() }
        if (trimmed.isEmpty()) return null
        if (!trimmed.any { it.isWhitespace() } && VPA.matches(trimmed)) return trimmed.lowercase()
        val words = trimmed.lowercase().replace("&", " and ").replace(NOT_WORD, " ").trim().split(SPACES)
            .filter { it.isNotEmpty() }
            .map { ENDINGS[it] ?: it }
        return words.joinToString(" ").ifEmpty { null }
    }

    /** True when [payee] is a UPI id rather than a name. */
    fun isVpa(payee: String): Boolean = payee.trim().let { !it.any { c -> c.isWhitespace() } && VPA.matches(it) }

    private val ENDINGS = mapOf("private" to "pvt", "limited" to "ltd", "pvt" to "pvt", "ltd" to "ltd")
}
