package com.zyke.message.presentation.format

import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MessageCategory
import java.util.Locale

private val EMAIL_LIKE = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

/** "SOME CAFE" -> "Some Cafe"; words that look like UPI ids are left alone. */
fun String.toTitleCase(): String {
    if (isEmpty()) return this
    val cached = synchronized(titleCases) { titleCases[this] }
    if (cached != null) return cached
    val result = lowercase(Locale.ROOT).split(" ").joinToString(" ") { word ->
        // Only a word with an @ can be an id; the pattern is not run on any other word, and not on a very long one.
        if ('@' in word && word.length <= MAX_ID_LENGTH && EMAIL_LIKE.matches(word)) word else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
    synchronized(titleCases) { titleCases[this] = result }
    return result
}

private const val MAX_ID_LENGTH = 120

/** Names are shown over and over while a list scrolls; the answer for a name is kept (the least recently used go first). */
private val titleCases = object : LinkedHashMap<String, String>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 2_000
}

/** UPI ids are shown verbatim, everything else is title-cased. */
val Merchant.displayName: String
    get() = if (renamed || category == MessageCategory.UPI_PAYMENT) name else name.toTitleCase()
