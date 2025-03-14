package com.zyke.message.model

data class ZBankMessage(
    val bankName: BankName,
    val accountNumber: String,
    val payee: String,
    val amount: String,
    val transactionType: BankTransactionType
)

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
    SENT("Paid"), RECEIVED("Received"), CARD_BILL("Bill Generated");

    companion object {
        fun BankTransactionType.isReceived() = this == RECEIVED
    }
}
