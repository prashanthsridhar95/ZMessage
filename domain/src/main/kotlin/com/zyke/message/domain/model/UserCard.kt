package com.zyke.message.domain.model

enum class CardType(val label: String) { CREDIT("Credit card"), DEBIT("Debit card") }

/** A card the person told us about, so messages can be matched to it by its last four digits. */
data class UserCard(
    val id: String,
    val type: CardType,
    val last4: String,
    /** What the person calls it, e.g. "HDFC Regalia". */
    val name: String,
    /** Day of the month the statement is generated (1-31); the cycle ends that day. Null means calendar months. */
    val statementDay: Int? = null
) {
    val label: String get() = name.ifBlank { type.label } + " •• " + last4
}
