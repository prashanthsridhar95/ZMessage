package com.zyke.message.domain.model

import java.time.LocalDate

enum class BankName(val displayString: String, val isCard: Boolean) {
    HDFC("HDFC", true),
    AXIS("Axis", true),
    FEDERAL_BANK("Federal Bank", true),
    AMERICAN_EXPRESS("American Express", true),
    LAZYPAY("Lazypay", false),
    SIMPL("Simpl", false),
    SBI("SBI", true),
    ICICI("ICICI", true),
    KOTAK("Kotak", true),
    IDFC_FIRST("IDFC First", true),
    YES_BANK("Yes Bank", true),
    PNB("PNB", true),
    CANARA("Canara", true),
    BANK_OF_BARODA("Bank of Baroda", true),
    INDUSIND("IndusInd", true),
    PAYTM("Paytm", false),
    INDIAN_BANK("Indian Bank", true),
    MUTUAL_FUND("Mutual Fund", false),
    PETPOOJA("", false),
    OTHERS("", false)
}

enum class BankTransactionType(val displayString: String) {
    SENT("Paid"), RECEIVED("Received"), CARD_BILL("Bill Generated"),
    UPCOMING_DEBIT("Auto-debit"), MANDATE_SET("Autopay set up"),
    MANDATE_CANCELLED("Autopay cancelled"), MANDATE_REQUEST("Autopay request")
}

/** Raw result of parsing a bank / card / pay-later SMS. */
data class BankTransaction(
    val bankName: BankName,
    val accountNumber: String,
    val payee: String,
    val amount: String,
    val type: BankTransactionType,
    val dueDate: LocalDate? = null,
    val minimumDue: String? = null,
    val paymentLink: String? = null
)
