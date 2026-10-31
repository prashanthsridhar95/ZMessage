package com.zyke.message.domain.model

import com.zyke.message.domain.cards.CardCatalog
import com.zyke.message.domain.cards.CardNetwork
import com.zyke.message.domain.cards.CardTheme
import com.zyke.message.domain.cards.CardThemes

enum class CardType(val label: String) {
    CREDIT("Credit card"), DEBIT("Debit card"), ACCOUNT("Bank account"), PREPAID("Prepaid card"), FOREX("Forex card");

    val isCard: Boolean get() = this != ACCOUNT

    /** Spends come straight out of money already held (debit, prepaid, forex), unlike credit that is billed later. */
    val isDebitLike: Boolean get() = this == DEBIT || this == PREPAID || this == FOREX
}

/** A card or bank account the person told us about, so messages can be matched to it by its last four digits. */
data class UserCard(
    val id: String,
    val type: CardType,
    val last4: String,
    /** What the person calls it, e.g. "HDFC Regalia". */
    val name: String,
    /** Day of the month the statement is generated (1-31); the cycle ends that day. Null means calendar months. */
    val statementDay: Int? = null,
    val bankName: String = "",
    /** The payment network (Visa, Mastercard...); null when not chosen. */
    val network: CardNetwork? = null,
    /** The card's product or model, e.g. "Regalia" or "Amazon Pay ICICI"; free text. */
    val variant: String = "",
    /** The id of a [CardThemes] colour theme; blank means one chosen automatically from the bank. */
    val themeId: String = "",
    /** Day of the month the bill falls due (1-31). */
    val dueDay: Int? = null,
    /** The card's credit limit in whole rupees. */
    val creditLimit: Long? = null
) {
    /** The colour theme to draw the card in: the chosen one, else one derived from the bank. */
    val theme: CardTheme get() = CardThemes.resolve(themeId, bankName.ifBlank { name })

    /** The bank as it should read on the card face. */
    val bankLabel: String get() = bankName.ifBlank { CardCatalog.bank(name)?.displayString.orEmpty() }

    /** What a screen reader says for the card, e.g. "HDFC credit card ending 1234, Visa, Regalia". */
    val spokenDescription: String
        get() = listOfNotNull(
            listOf(bankLabel, type.label.lowercase().replace("bank account", "account")).filter { it.isNotBlank() }.joinToString(" ") + " ending $last4",
            network?.label,
            variant.takeIf { it.isNotBlank() }
        ).joinToString(", ")

    val label: String
        get() {
            val title = name.ifBlank { bankName.ifBlank { type.label } }
            val marker = if (type == CardType.ACCOUNT) " A/c •• " else " •• "
            return title + marker + last4
        }
}
