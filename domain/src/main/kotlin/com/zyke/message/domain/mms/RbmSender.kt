package com.zyke.message.domain.mms

/**
 * RCS Business Messaging (RBM) agents are addressed like "hdfc_bank_gkjmtq7y_agent@rbm.google.com": the brand, a random id the
 * platform adds, the word "agent", and an RBM domain. When Google Messages keeps such a rich card in the MMS provider, that address
 * is the only sender there is. This turns it into the name a person knows ("HDFC Bank"), which is also what the notification of the
 * same card is called, so both resolve to the same bank and logo.
 *
 * The real layout of these addresses is inferred from examples, not verified on a device; anything that does not look like an RBM
 * address is returned untouched.
 */
object RbmSender {
    private val domain = Regex("(?i)^rbm\\.goog(?:le(?:\\.com)?)?$")

    /** Words always written in capitals. */
    internal val acronyms = setOf(
        "hdfc", "sbi", "icici", "upi", "idfc", "pnb", "rbl", "hsbc", "uco", "bob", "idbi", "npci", "lic", "emi", "atm", "kvb", "irctc"
    )

    /** True for "<something>@rbm.google.com" and "@rbm.goog". */
    fun isRbm(address: String): Boolean {
        val at = address.trim().lastIndexOf('@')
        return at > 0 && domain.matches(address.trim().substring(at + 1))
    }

    /** The friendly name of an RBM agent address; any other text is returned as it came. */
    fun friendly(address: String): String {
        val trimmed = address.trim()
        if (!isRbm(trimmed)) return address
        val tokens = trimmed.substring(0, trimmed.lastIndexOf('@')).lowercase()
            .split('_', '-', '.', ' ').filter { it.isNotEmpty() }.toMutableList()
        if (tokens.size > 1 && tokens.last() == "agent") tokens.removeAt(tokens.lastIndex)
        if (tokens.size > 1 && looksLikeId(tokens.last())) tokens.removeAt(tokens.lastIndex)
        if (tokens.isEmpty()) return address
        return tokens.joinToString(" ") { if (it in acronyms) it.uppercase() else it.replaceFirstChar { c -> c.uppercase() } }
    }

    /**
     * The random id the platform puts after the brand: 6 to 12 letters and digits that either hold a digit, or are letters only
     * with no vowel or a run of four consonants (a real word such as "chartered" has neither). A word of the brand is never taken
     * for an id because the brand's first word is never tested (at least one token must remain).
     */
    private fun looksLikeId(token: String): Boolean {
        if (token.length !in 6..12 || !token.all { it in 'a'..'z' || it in '0'..'9' }) return false
        if (token.any { it in '0'..'9' }) return true
        val vowels = "aeiou"
        if (token.none { it in vowels }) return true
        var run = 0
        for (c in token) {
            run = if (c in vowels) 0 else run + 1
            if (run >= 4) return true
        }
        return false
    }
}
