package com.zyke.message.domain.nickname

import com.zyke.message.domain.model.MessageCategory

/** A name the person has already given to something, and how many things carry it. */
data class NicknameSuggestion(val name: String, val uses: Int)

/** The category and picture a name already has somewhere else, to carry over to a new thing given the same name. */
data class InheritedDetails(val sourceKey: String, val category: MessageCategory?, val imageKey: String?) {
    val isEmpty: Boolean get() = category == null && imageKey == null
}

object NicknameSuggestions {
    private const val DEFAULT_LIMIT = 6

    /**
     * The names already in use that fit what is being typed, best first: the same name, then names that start with it,
     * then names with a word that starts with it, then names that contain it, then names that hold its letters in order.
     * Ties go to the name used on more things. With nothing typed, the most used come first.
     */
    fun suggest(typed: String, nicknames: Map<String, String>, limit: Int = DEFAULT_LIMIT): List<NicknameSuggestion> {
        val uses = nicknames.values.map { it.trim() }.filter { it.isNotEmpty() }.groupingBy { it.lowercase() }.eachCount()
        val display = nicknames.values.map { it.trim() }.filter { it.isNotEmpty() }.associateBy { it.lowercase() }
        val q = typed.trim().lowercase()

        return uses.keys.mapNotNull { name ->
            val rank = if (q.isEmpty()) 0 else rank(name, q) ?: return@mapNotNull null
            Triple(rank, name, uses.getValue(name))
        }
            .sortedWith(compareBy<Triple<Int, String, Int>> { it.first }.thenByDescending { it.third }.thenBy { it.second.length }.thenBy { it.second })
            // What is already typed in full is not offered back as a suggestion.
            .filter { it.second != q }
            .take(limit)
            .map { NicknameSuggestion(display.getValue(it.second), it.third) }
    }

    private fun rank(name: String, q: String): Int? = when {
        name.startsWith(q) -> 1
        name.split(' ', '-', '&', '.', '(', ')').any { it.startsWith(q) } -> 2
        name.contains(q) -> 3
        inOrder(name, q) -> 4
        else -> null
    }

    /** Whether every letter of [q] appears in [name], in the same order: "swgy" finds "swiggy". */
    private fun inOrder(name: String, q: String): Boolean {
        var at = 0
        for (c in q) {
            at = name.indexOf(c, at)
            if (at < 0) return false
            at += 1
        }
        return true
    }

    /**
     * When something is given a name that is already used elsewhere, what that name already has: its category (the one
     * most of its holders share) and a picture (from any holder that has one). They may come from different holders.
     * Null when the name is new or none of its holders has either.
     */
    fun inherit(
        name: String,
        targetKey: String,
        nicknames: Map<String, String>,
        categories: Map<String, MessageCategory>,
        imageKeys: Set<String>
    ): InheritedDetails? {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        val holders = nicknames.entries.filter { it.key != targetKey && it.value.trim().lowercase() == wanted }.map { it.key }.sorted()
        val category = holders.mapNotNull { categories[it] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val image = holders.firstOrNull { it in imageKeys }
        if (category == null && image == null) return null
        return InheritedDetails(sourceKey = image ?: holders.first { categories.containsKey(it) }, category = category, imageKey = image)
    }
}
