package com.zyke.message.domain.conversation

/**
 * A sender id that is a consonant skeleton of a brand ("FRMILK" for FreshMilk, "BGBSKT" for BigBasket) is named by the brand's own
 * spelling when the message body writes it out. Nothing is hard-coded to a brand: the id's letters must run, in order, through one
 * word of the body (or two neighbouring words, "Fresh Milk"), starting and ending on the same letters, and cover at least half of it.
 */
object BrandSpelling {
    private const val MIN_LETTERS = 4
    private const val MIN_COVERAGE = 0.5
    private const val MAX_BODY = 600

    private val WORD = Regex("[A-Za-z]+")

    /** The body's spelling of the brand [id] abbreviates, or null when no word of [body] fits. */
    fun fromBody(id: String, body: String): String? {
        val letters = id.filter { it.isLetter() }.lowercase()
        if (letters.length < MIN_LETTERS) return null
        val words = WORD.findAll(body.take(MAX_BODY)).map { it.value }.toList()
        val candidates = ArrayList<String>(words.size * 2)
        for (i in words.indices) {
            candidates += words[i]
            if (i + 1 < words.size) candidates += words[i] + " " + words[i + 1]
        }
        val fits = candidates.filter { fits(letters, it) }
        if (fits.isEmpty()) return null
        // The same brand is often written twice ("Freshmilk", "FreshMilk"): the one with a capital inside is the brand's own.
        return fits.firstOrNull { word -> word.drop(1).any { it.isUpperCase() } && word.none { it == ' ' } } ?: fits.first()
    }

    private fun fits(id: String, candidate: String): Boolean {
        val word = candidate.filter { it.isLetter() }.lowercase()
        if (word.length < id.length || id.length < word.length * MIN_COVERAGE) return false
        if (word.first() != id.first() || word.last() != id.last()) return false
        var at = 0
        for (c in word) if (at < id.length && c == id[at]) at++
        return at == id.length
    }
}
