package com.zyke.message.domain.categories

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

/** Where the category, name or picture a payment shows came from. Highest first: [THIS_TRANSACTION], [PAYEE], [WORD_RULE], [AUTOMATIC]. */
enum class RuleSource { THIS_TRANSACTION, PAYEE, WORD_RULE, AUTOMATIC }

/**
 * What a payment's rows in the detail sheet need to say about the person's rules: who the payment is with, where each
 * of its category, name and picture comes from, and how many other payments a payee-wide choice would change.
 *
 * The chain (the pipeline in ObserveMessagesUseCase applies it, this only explains the result):
 * this payment's own choice, then the choice for its payee (the receiver or payer named in the text; a message that
 * names none is keyed by its sender), then a word rule (category only), then what the app worked out itself.
 */
object TransactionRules {

    /** The person or shop a payment was with, as written in the text; null for a message that names nobody. */
    fun payeeName(message: ClassifiedMessage): String? =
        (message.content as? MessageContent.Transaction)?.payee?.trim()?.takeIf { it.isNotEmpty() }

    /** Category: its own choice, the payee's (or sender's) choice, a word rule, or automatic. [overrides] are the stored choices. */
    fun categorySource(message: ClassifiedMessage, overrides: Map<String, *>): RuleSource = when {
        message.categoryOwn -> RuleSource.THIS_TRANSACTION
        !message.categoryOverridden -> RuleSource.AUTOMATIC
        // Filed under one of the person's own categories, or by a stored choice for the key: a payee rule. Only a match by word is not.
        message.customCategoryId != null || CategoryKey.find(overrides, message) != null -> RuleSource.PAYEE
        else -> RuleSource.WORD_RULE
    }

    /** Name: given to this payment, given to its payee, or none. */
    fun nicknameSource(message: ClassifiedMessage): RuleSource = when {
        message.nicknameOwn -> RuleSource.THIS_TRANSACTION
        message.merchant.renamed -> RuleSource.PAYEE
        else -> RuleSource.AUTOMATIC
    }

    /** Picture: chosen for this payment, chosen for its payee, or none (a brand logo or the initial is automatic). [pictureKeys] are the keys with a chosen picture. */
    fun pictureSource(message: ClassifiedMessage, pictureKeys: Set<String>): RuleSource = when {
        CategoryKey.messageKey(message.id) in pictureKeys -> RuleSource.THIS_TRANSACTION
        CategoryKey.of(message)?.let { it in pictureKeys } == true || CategoryKey.legacyOf(message)?.let { it in pictureKeys } == true -> RuleSource.PAYEE
        else -> RuleSource.AUTOMATIC
    }

    /** The other payments that share [message]'s payee key (a payee-wide choice changes these too). */
    fun othersWithSamePayee(message: ClassifiedMessage, all: List<ClassifiedMessage>): List<ClassifiedMessage> {
        val key = CategoryKey.of(message) ?: return emptyList()
        return all.filter { it.id != message.id && CategoryKey.of(it) == key }
    }

    /** "Also changes 7 payments", or null when it changes no other. */
    fun alsoChanges(others: Int): String? = when {
        others <= 0 -> null
        others == 1 -> "Also changes 1 other payment"
        else -> "Also changes $others other payments"
    }

    /**
     * What a row says about where its value came from: "Only this transaction", "Mr Diy rule", "Automatic".
     * [payee] is the name to call the payee rule after; [word] is the word-rule wording.
     */
    fun sourceText(source: RuleSource, payee: String): String = when (source) {
        RuleSource.THIS_TRANSACTION -> "Only this transaction"
        RuleSource.PAYEE -> "$payee rule"
        RuleSource.WORD_RULE -> "Word rule"
        RuleSource.AUTOMATIC -> "Automatic"
    }
}
