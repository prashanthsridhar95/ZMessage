package com.zyke.message.domain.privacy

/**
 * Finds money amounts inside any sentence and swaps them for dots, without a regex (it runs on every text on screen
 * while amounts are hidden, so it is one cheap pass that returns the same string when there is nothing to hide).
 *
 * An amount is a rupee sign, "Rs", "Rs." or "INR" followed by digits with optional grouping commas and a decimal part:
 * "₹1,23,456.78", "₹ 500", "Rs. 500", "INR500". Ordinary numbers (dates, counts, percentages, card last-4) are never touched,
 * and punctuation after the amount ("₹500." or "₹500,") stays.
 */
object AmountMasker {
    const val PLACEHOLDER = "₹ ••••"

    /** What a screen reader says in place of [PLACEHOLDER]. */
    const val SPOKEN = "amount hidden"

    /** Start (inclusive) and end (exclusive) of each amount in [text], in order. Empty when there is none. */
    fun find(text: CharSequence): List<IntRange> {
        var found: ArrayList<IntRange>? = null
        var i = 0
        val n = text.length
        while (i < n) {
            val start = i
            val afterSign = signEnd(text, i, n)
            if (afterSign < 0) { i++; continue }
            val end = numberEnd(text, afterSign, n)
            if (end < 0) { i++; continue }
            if (found == null) found = ArrayList(2)
            found.add(start until end)
            i = end
        }
        return found ?: emptyList()
    }

    fun containsAmount(text: CharSequence): Boolean = find(text).isNotEmpty()

    /** [text] with every amount replaced by [placeholder]; the same instance when there is none. */
    fun mask(text: String, placeholder: String = PLACEHOLDER): String {
        val spans = find(text)
        if (spans.isEmpty()) return text
        val out = StringBuilder(text.length)
        var from = 0
        for (s in spans) {
            out.append(text, from, s.first).append(placeholder)
            from = s.last + 1
        }
        return out.append(text, from, text.length).toString()
    }

    /** [text] for a screen reader: each amount becomes "amount hidden". */
    fun maskSpoken(text: String): String = mask(text, SPOKEN)

    /** Where the currency marker that starts at [i] ends (before any spaces), or -1 when none starts here. */
    private fun signEnd(t: CharSequence, i: Int, n: Int): Int {
        val c = t[i]
        if (c == '₹') return i + 1
        if ((c == 'R' || c == 'r') && i + 1 < n && (t[i + 1] == 's' || t[i + 1] == 'S') && boundaryBefore(t, i)) {
            var j = i + 2
            if (j < n && t[j] == '.') j++
            return j
        }
        if ((c == 'I' || c == 'i') && i + 2 < n && (t[i + 1] == 'N' || t[i + 1] == 'n') && (t[i + 2] == 'R' || t[i + 2] == 'r') &&
            boundaryBefore(t, i)
        ) return i + 3
        return -1
    }

    private fun boundaryBefore(t: CharSequence, i: Int): Boolean = i == 0 || !t[i - 1].isLetterOrDigit()

    /** After the marker: optional spaces, then a number. Returns where the number ends, or -1 when no digit follows. */
    private fun numberEnd(t: CharSequence, from: Int, n: Int): Int {
        var j = from
        while (j < n && (t[j] == ' ' || t[j] == ' ')) j++
        if (j >= n || !isDigit(t[j])) return -1
        while (j < n) {
            val c = t[j]
            if (isDigit(c)) { j++; continue }
            // A comma or point belongs to the amount only when a digit follows ("₹1,200" yes, "₹1,200, and" no).
            if ((c == ',' || c == '.') && j + 1 < n && isDigit(t[j + 1])) { j++; continue }
            break
        }
        return j
    }

    private fun isDigit(c: Char) = c in '0'..'9'
}
