package com.zyke.message.domain.search

/**
 * Where typed words appear in a piece of text, for drawing them bold. Plain `indexOf`, no regular expression, so it is
 * cheap enough to run once per row. The ranges are in order, never overlap, and touching ones are merged.
 */
object TextHighlight {

    /** The words of a query: split on spaces, empty pieces dropped. */
    fun words(query: String): List<String> = query.trim().split(' ').filter { it.isNotEmpty() }

    /**
     * Half-open ranges (start until end) of [text] covered by any of [words], ignoring case. Overlapping and touching
     * matches are merged into one range. An empty word, empty words or empty text give no ranges.
     */
    fun ranges(text: String, words: List<String>): List<IntRange> {
        if (text.isEmpty() || words.isEmpty()) return emptyList()
        val marked = BooleanArray(text.length)
        var any = false
        for (word in words) {
            if (word.isEmpty()) continue
            var at = text.indexOf(word, 0, ignoreCase = true)
            while (at >= 0) {
                for (i in at until at + word.length) marked[i] = true
                any = true
                at = text.indexOf(word, at + word.length, ignoreCase = true)
            }
        }
        if (!any) return emptyList()
        val result = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            if (!marked[i]) {
                i++
                continue
            }
            var j = i
            while (j < text.length && marked[j]) j++
            result += i until j
            i = j
        }
        return result
    }
}
