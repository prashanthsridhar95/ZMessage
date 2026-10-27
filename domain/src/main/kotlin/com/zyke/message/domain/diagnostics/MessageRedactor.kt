package com.zyke.message.domain.diagnostics

/**
 * Removes what identifies a person from an SMS while keeping its shape, so a message that
 * failed to parse can be reported without handing over account numbers or names.
 * It is a best-effort aid: always glance over the result before sharing it.
 */
object MessageRedactor {
    private val UPI_OR_EMAIL = Regex("[A-Za-z0-9._*\\-]{1,}@([A-Za-z.]{2,})")
    private val LINK = Regex("https?://\\S+")
    private val DIGITS = Regex("\\d")

    // Words that look like names (capitalised) but are labels or bank vocabulary the parser author needs.
    private const val NOT_A_NAME =
        "(?!(?:On|Ref|UPI|Avl|Bal|Not|If|Call|Info|Txn|Rs|INR|Bank|Card|Account|Acct|A/C|A/c|HDFC|ICICI|SBI|Axis|Kotak|Federal|Amex)\\b)"
    private const val NAME_WORD = "$NOT_A_NAME[A-Z][A-Za-z&.']*"

    // A name after a preposition on the same line: "To BURMY FOODS", "from John Smith", "at Cafe Bean".
    private val NAME_AFTER_WORD = Regex("\\b((?i:trf to|paid to|sent to|towards|from|to|at|by|for))[ \\t]+($NAME_WORD(?:[ \\t]+$NAME_WORD){0,3})")

    fun redact(body: String): String {
        var text = body.trim()
        text = LINK.replace(text, "<link>")
        text = UPI_OR_EMAIL.replace(text) { "xxxx@${it.groupValues[1]}" }
        text = NAME_AFTER_WORD.replace(text) { match -> "${match.groupValues[1]} [name]" }
        // Digits last, so amounts keep their shape (Rs.540.00 -> Rs.999.99) but nothing is readable.
        return DIGITS.replace(text, "9")
    }
}
