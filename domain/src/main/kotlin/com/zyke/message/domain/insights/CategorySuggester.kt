package com.zyke.message.domain.insights

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import kotlin.math.exp
import kotlin.math.ln

/**
 * Learns, on the phone, which words in a sender / receiver name go with which category, from the messages
 * that are already filed (by the built-in rules or by the person), and suggests a category for names that
 * are not. A small naive Bayes model: no download, no network, nothing leaves the device.
 */
class CategorySuggester private constructor(
    private val wordCounts: Map<MessageCategory, Map<String, Int>>,
    private val categoryTotals: Map<MessageCategory, Int>,
    private val vocabulary: Int
) {
    /** The most likely category and how sure the model is (0..1), or null when it has nothing to go on. */
    fun suggest(name: String): Pair<MessageCategory, Double>? {
        val words = words(name)
        if (words.isEmpty() || categoryTotals.isEmpty()) return null
        if (words.none { word -> wordCounts.values.any { word in it } }) return null

        val totalDocs = categoryTotals.values.sum().toDouble()
        val scores = categoryTotals.keys.associateWith { category ->
            val counts = wordCounts[category].orEmpty()
            val wordsInCategory = counts.values.sum().toDouble()
            ln(categoryTotals.getValue(category) / totalDocs) + words.sumOf { word ->
                ln(((counts[word] ?: 0) + 1.0) / (wordsInCategory + vocabulary))
            }
        }
        val best = scores.maxByOrNull { it.value } ?: return null
        val normaliser = scores.values.sumOf { exp(it - best.value) }
        return best.key to 1.0 / normaliser
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.6
        private const val MIN_WORD = 3
        private val NOT_LETTERS = Regex("[^a-z]+")
        private val SKIP = setOf("the", "and", "pvt", "ltd", "limited", "private", "upi", "bank", "payment", "payments", "store", "stores")

        private val LEARNABLE = MessageCategory.assignable.toSet() - setOf(
            MessageCategory.UPI_PAYMENT, MessageCategory.CREDIT_CARDS, MessageCategory.NETWORK_CARRIERS
        )

        internal fun words(name: String): List<String> =
            name.lowercase().split(NOT_LETTERS).filter { it.length >= MIN_WORD && it !in SKIP }.distinct()

        fun learnFrom(messages: List<ClassifiedMessage>): CategorySuggester {
            val counts = HashMap<MessageCategory, HashMap<String, Int>>()
            val totals = HashMap<MessageCategory, Int>()
            val seenKeys = HashSet<String>()

            messages.forEach { message ->
                if (message.category !in LEARNABLE) return@forEach
                // One vote per sender / receiver, so a daily coffee does not outweigh everything else.
                val key = CategoryKey.of(message) ?: return@forEach
                if (!seenKeys.add(key)) return@forEach
                val words = words(CategoryKey.label(message))
                if (words.isEmpty()) return@forEach
                totals[message.category] = (totals[message.category] ?: 0) + 1
                val bucket = counts.getOrPut(message.category) { HashMap() }
                words.forEach { bucket[it] = (bucket[it] ?: 0) + 1 }
            }
            val vocabulary = counts.values.flatMap { it.keys }.toSet().size.coerceAtLeast(1)
            return CategorySuggester(counts, totals, vocabulary)
        }

        /** Adds a suggestion to every uncategorised message the model is confident about. */
        fun annotate(messages: List<ClassifiedMessage>): List<ClassifiedMessage> {
            val suggester = learnFrom(messages)
            return messages.map { message ->
                if (message.category != MessageCategory.OTHERS || message.isPromotional) return@map message
                val name = CategoryKey.label(message)
                val (category, confidence) = suggester.suggest(name) ?: return@map message
                if (confidence >= MIN_CONFIDENCE && category in LEARNABLE) message.copy(suggestedCategory = category) else message
            }
        }
    }
}
