package com.zyke.message.domain.nickname

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.model.ClassifiedMessage

/**
 * What the picture search for a payee or sender starts with. It is the name the person sees: their nickname if they gave
 * one, else the name shown, else the name in the message. The name in the message is offered as a second search
 * ("Search for MR DIY instead") and the person can edit the text before searching.
 *
 * The text is sent to a web search, so it is only ever a name: a word with digits in it that looks like an account, card,
 * phone or reference number, an amount, and the part of a UPI id after the "@" never go.
 */
object PictureSearchQuery {

    /** [primary] is what the search field starts with ("" when no clean name exists); [alternative] a different clean name to offer instead. */
    data class Suggestion(val primary: String, val alternative: String?)

    private const val MAX_LENGTH = 60
    private val SEPARATORS = Regex("[._\\-/*|]+")
    private val SPACES = Regex("\\s+")
    private val AMOUNT = Regex("(?i)(?:rs\\.?|inr|\u20B9)\\s*\\d[\\d,]*(?:\\.\\d+)?|\\d[\\d,]*\\.\\d+")
    private val ACCOUNT_WORD = Regex("(?i)\\ba/c\\b")
    private val MASKED = Regex("(?i)^[x*#]+\\d*$")

    /** [raw] reduced to a name that is safe to search for; "" when nothing of it is a name. */
    fun clean(raw: String): String {
        val local = raw.substringBefore('@').replace(AMOUNT, " ").replace(ACCOUNT_WORD, " ")
        val words = local.replace(SEPARATORS, " ").replace(SPACES, " ").trim().split(' ')
            .filter { it.isNotEmpty() && !risky(it) }
        // A line of nothing but a currency marker or a bare number is no name.
        val text = words.joinToString(" ").trim().take(MAX_LENGTH).trim()
        return if (text.any { it.isLetter() }) text else ""
    }

    /** True for a word that may carry a private number: masked digits, a long number, or mostly digits. */
    private fun risky(word: String): Boolean {
        if (MASKED.matches(word)) return true
        val digits = word.count { it.isDigit() }
        if (digits == 0) return false
        if (digits >= 4) return true
        val letters = word.count { it.isLetter() }
        // "7 Eleven" and "Studio 21" are names; "123" (an amount, a short code) and "A12" are not worth sending.
        return if (letters == 0) digits >= 3 else digits > letters
    }

    /**
     * The search for a name the person sees ([nickname] first, then [shown]) and the name in the message ([original]).
     * The alternative is the original when it differs from the primary once spacing and case are ignored.
     */
    fun of(nickname: String?, shown: String?, original: String?): Suggestion {
        val primary = listOf(nickname, shown, original).map { clean(it.orEmpty()) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val cleanOriginal = clean(original.orEmpty())
        val alternative = cleanOriginal.takeIf {
            it.isNotEmpty() && NicknameProfiles.normalise(it) != NicknameProfiles.normalise(primary)
        }
        return Suggestion(primary, alternative)
    }

    /** The search for [message]: its nickname if the person gave one, else its name, with the name in the message as the alternative. */
    fun of(message: ClassifiedMessage): Suggestion {
        val merchant = message.merchant
        val original = merchant.originalName ?: CategoryKey.label(message)
        return of(
            nickname = merchant.name.takeIf { merchant.renamed && merchant.originalName != null },
            shown = merchant.name,
            original = original
        )
    }

    /**
     * The search for the picture of a payee as a whole, when the message shown may carry a name of its own for this payment only:
     * [payeeNickname] is the name given to the payee (null when none), and the message's own name never leads.
     */
    fun forPayee(message: ClassifiedMessage, payeeNickname: String?): Suggestion {
        val merchant = message.merchant
        val original = merchant.originalName ?: CategoryKey.label(message)
        // A name given to this payment alone is not the payee's: the name shown for the payee is then the one in the message.
        val shown = if (merchant.renamed && merchant.originalName != null) original else merchant.name
        return of(payeeNickname, shown, original)
    }
}
