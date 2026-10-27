package com.zyke.message.domain.dashboard

import com.zyke.message.domain.model.BankName
import java.math.BigDecimal
import java.time.LocalDate

/** One statement for a card. */
data class BillEntry(
    val messageId: Long,
    val epochMillis: Long,
    val amount: BigDecimal,
    val minimumDue: BigDecimal?,
    val dueDate: LocalDate?,
    val paymentLink: String?
)

/** All the bills we know of for one card, latest first. */
data class CardBills(
    val key: String,
    val bankName: BankName,
    val accountNumber: String,
    /** e.g. "HDFC Card •• 1234". */
    val label: String,
    val latest: BillEntry,
    /** Earlier statements, newest first. */
    val earlier: List<BillEntry>
)
