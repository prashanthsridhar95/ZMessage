package com.zyke.message.domain.model

enum class BankName(val displayString: String, val isCard: Boolean) {
    HDFC("HDFC", true),
    AXIS("Axis", true),
    FEDERAL_BANK("Federal Bank", true),
    AMERICAN_EXPRESS("American Express", true),
    LAZYPAY("Lazypay", false),
    SIMPL("Simpl", false),
    PETPOOJA("", false),
    OTHERS("", false)
}

enum class BankTransactionType(val displayString: String) {
    SENT("Paid"), RECEIVED("Received"), CARD_BILL("Bill Generated")
}

/** Raw result of parsing a bank / card / pay-later SMS. */
data class BankTransaction(
    val bankName: BankName,
    val accountNumber: String,
    val payee: String,
    val amount: String,
    val type: BankTransactionType
)
