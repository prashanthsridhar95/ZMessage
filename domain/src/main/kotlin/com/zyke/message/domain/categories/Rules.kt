package com.zyke.message.domain.categories

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory

/** One thing the person taught the app about a sender / receiver (or, for [single], about one payment). */
data class TaughtRule(
    val key: String,
    /** Readable name for the key; a generic one when no loaded message has it. */
    val label: String,
    val category: MessageCategory?,
    val nickname: String?,
    val hasPicture: Boolean,
    /** How many of the loaded messages it applies to now. */
    val affects: Int,
    val single: Boolean
)

/** "Anything with [token] files under [category]". */
data class TokenRuleRow(val token: String, val category: MessageCategory, val affects: Int)

/** A suggestion to turn repeated choices into a word rule. */
data class TokenSuggestion(val token: String, val category: MessageCategory, val receivers: Int)

data class RulesOverview(val rules: List<TaughtRule>, val singles: List<TaughtRule>, val tokenRules: List<TokenRuleRow>) {
    val isEmpty: Boolean get() = rules.isEmpty() && singles.isEmpty() && tokenRules.isEmpty()
}

object Rules {
    const val MIN_RECEIVERS = 3
    const val UNKNOWN_PAYEE = "a payee"
    const val UNKNOWN_PAYMENT = "a payment"

    fun describe(
        messages: List<ClassifiedMessage>,
        categoryOverrides: Map<String, MessageCategory>,
        nicknames: Map<String, String>,
        imageKeys: Set<String>,
        tokenRules: Map<String, MessageCategory> = emptyMap()
    ): RulesOverview {
        val byKey = messages.groupBy { CategoryKey.of(it) }
        val byId = messages.associateBy { it.id }
        val keys = (categoryOverrides.keys + nicknames.keys).toSortedSet()
        val rows = keys.map { key ->
            val single = key.startsWith("msg:")
            val own = if (single) key.removePrefix("msg:").toLongOrNull()?.let(byId::get) else null
            val group = if (single) listOfNotNull(own) else byKey[key].orEmpty()
            TaughtRule(
                key = key,
                label = group.firstOrNull()?.let(CategoryKey::label)?.ifBlank { null } ?: if (single) UNKNOWN_PAYMENT else UNKNOWN_PAYEE,
                category = categoryOverrides[key],
                nickname = nicknames[key],
                hasPicture = key in imageKeys,
                affects = group.size,
                single = single
            )
        }.sortedBy { it.label.lowercase() }
        val words = tokenRules.map { (token, category) ->
            val hits = messages.count { m ->
                val key = CategoryKey.of(m)
                key != null && !categoryOverrides.containsKey(key) && !categoryOverrides.containsKey(CategoryKey.messageKey(m.id)) &&
                    Tokens.match(key, mapOf(token to category)) != null
            }
            TokenRuleRow(token, category, hits)
        }.sortedBy { it.token }
        return RulesOverview(rows.filter { !it.single }, rows.filter { it.single }, words)
    }

    /**
     * Words shared by at least [MIN_RECEIVERS] distinct senders / receivers the person filed under the same category,
     * with no word rule yet. Single-payment choices do not count.
     */
    fun suggestTokenRules(categoryOverrides: Map<String, MessageCategory>, tokenRules: Map<String, MessageCategory>): List<TokenSuggestion> {
        val perWord = HashMap<Pair<String, MessageCategory>, Int>()
        for ((key, category) in categoryOverrides) {
            if (key.startsWith("msg:")) continue
            for (word in Tokens.of(key)) perWord.merge(word to category, 1, Int::plus)
        }
        return perWord.entries
            .filter { (pair, count) ->
                val (word, category) = pair
                count >= MIN_RECEIVERS && word !in tokenRules &&
                    // A word that is filed under two categories about as often is no rule.
                    perWord.entries.none { (other, n) -> other.first == word && other.second != category && n >= count }
            }
            .map { (pair, count) -> TokenSuggestion(pair.first, pair.second, count) }
            .sortedWith(compareByDescending<TokenSuggestion> { it.receivers }.thenBy { it.token })
    }

    /** Capitalises each word of a normalised key for display. */
    fun titleCase(key: String): String = key.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
