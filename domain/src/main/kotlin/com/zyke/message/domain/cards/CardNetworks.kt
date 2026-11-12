package com.zyke.message.domain.cards

import com.zyke.message.domain.model.BankName

/**
 * Which payment network a card runs on, read from what the messages and the card's own name say. It never guesses: where
 * nothing names a network, or the messages name two about equally, the answer is null and the card shows no mark.
 */
object CardNetworks {
    private val rupay = Regex("""\bru\s?pay\b""", RegexOption.IGNORE_CASE)
    private val mastercard = Regex("""\bmaster\s?card\b""", RegexOption.IGNORE_CASE)
    private val amex = Regex("""\bamex\b|\bamerican\s+express\b""", RegexOption.IGNORE_CASE)
    private val diners = Regex("""\bdiners\s?club\b""", RegexOption.IGNORE_CASE)

    // "Visa" alone can be a travel visa, so in a message it counts only beside a word that makes it a card.
    private val visaCard = Regex(
        """\bvisa\s+(?:signature|infinite|platinum|classic|gold|business|corporate|credit|debit|prepaid|card|electron|rewards|ending|xx+\d*)\b""" +
            """|\b(?:credit|debit|prepaid|forex)\s+card\s+(?:network\s+)?visa\b""",
        RegexOption.IGNORE_CASE
    )
    private val dinersWord = Regex("""\bdiners\b""", RegexOption.IGNORE_CASE)
    private val visaWord = Regex("""\bvisa\b""", RegexOption.IGNORE_CASE)

    /**
     * The network a piece of text names, or null. With [loose] the word "Visa" alone counts too: use it only on a text that is
     * already about a card (its product name), never on a message body.
     */
    fun fromText(text: String, loose: Boolean = false): CardNetwork? {
        if (text.length < 4) return null
        val found = buildList {
            if (rupay.containsMatchIn(text)) add(CardNetwork.RUPAY)
            if (mastercard.containsMatchIn(text)) add(CardNetwork.MASTERCARD)
            if (amex.containsMatchIn(text)) add(CardNetwork.AMEX)
            if ((if (loose) dinersWord else diners).containsMatchIn(text)) add(CardNetwork.DINERS)
            if ((if (loose) visaWord else visaCard).containsMatchIn(text)) add(CardNetwork.VISA)
        }
        return found.singleOrNull()
    }

    /** The network most of these messages name; null when none does, or when two are named equally often. */
    fun fromMessages(bodies: Sequence<String>): CardNetwork? {
        val votes = HashMap<CardNetwork, Int>()
        bodies.forEach { body -> fromText(body)?.let { votes[it] = (votes[it] ?: 0) + 1 } }
        val top = votes.maxOfOrNull { it.value } ?: return null
        return votes.entries.filter { it.value == top }.singleOrNull()?.key
    }

    /** What the bank and the card's own name say: American Express cards run on Amex; "Diners Club Black" on Diners. */
    fun fromProduct(bank: String, variant: String): CardNetwork? {
        if (CardCatalog.bank(bank) == BankName.AMERICAN_EXPRESS) return CardNetwork.AMEX
        return fromText(variant, loose = true) ?: fromText(bank, loose = false)
    }

    /**
     * The network to show: the person's own choice ([chosen]; [CardNetwork.OTHER] means "none"), else what the messages
     * name, else what the bank and product say. Null draws no mark.
     */
    fun resolve(chosen: CardNetwork?, fromMessages: CardNetwork?, bank: String, variant: String): CardNetwork? {
        val picked = chosen ?: fromMessages ?: fromProduct(bank, variant)
        return picked.takeIf { it != CardNetwork.OTHER }
    }
}
