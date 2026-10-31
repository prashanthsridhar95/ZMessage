package com.zyke.message.domain.categories

import com.zyke.message.domain.model.MessageCategory

/** The words of a normalised [CategoryKey], for matching senders / receivers that are alike. */
object Tokens {
    private val filler = setOf(
        "the", "and", "pvt", "ltd", "limited", "private", "india", "bank", "upi", "payment", "payments",
        "services", "service", "store", "stores", "mr", "mrs", "ms", "shri", "smt", "co", "corp", "inc", "llp"
    )

    /** Distinctive words of [key]: letters/digits, at least 3 long, not all digits, not common filler. In order, no repeats. */
    fun of(key: String): List<String> =
        key.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && !it.all(Char::isDigit) && it !in filler }
            .distinct()

    /** The category of the first word of [key] that has a rule, if any. */
    fun match(key: String, rules: Map<String, MessageCategory>): MessageCategory? {
        if (rules.isEmpty() || key.startsWith("msg:")) return null
        return of(key).firstNotNullOfOrNull(rules::get)
    }

    /** Words in common, Jaccard-style: 0.0 to 1.0. */
    fun similarity(a: String, b: String): Double {
        val x = of(a).toSet()
        val y = of(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }
}
