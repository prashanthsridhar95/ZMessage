package com.zyke.message.domain.export

import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.ledger.PaymentGroups
import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the person can take out of the app: their transactions as a spreadsheet. Message text is left out; only what was read from it is written.
 * Every transaction message is a row, as in the app's data; a payment that other messages repeat is marked "main, N related" in
 * the same_payment column and each repeat "repeat of the HDFC debit" (and says "no: same payment..." in counted_in_totals), so a sheet
 * can show one row per payment by filtering on that column. Nothing is dropped.
 */
object Exports {
    private val HEADER = listOf(
        "date", "time", "direction", "amount", "currency", "who", "category", "account", "bank", "kind", "from", "counted_in_totals", "same_payment", "note", "tags", "personal_note"
    )

    fun transactionsCsv(messages: List<ClassifiedMessage>, zone: ZoneId): String = buildString {
        appendLine(HEADER.joinToString(","))
        val groups = PaymentGroups.of(messages)
        messages.asSequence()
            .filter { it.content is MessageContent.Transaction }
            .sortedByDescending { it.message.date }
            .forEach { message ->
                val tx = message.content as MessageContent.Transaction
                val at = Instant.ofEpochMilli(message.message.date).atZone(zone)
                val counted = when {
                    SamePayment.isRepeat(message) -> "no: same payment as ${message.samePayment?.primaryLabel ?: "another message"}"
                    message.setAside != null -> "no: ${message.setAside!!.label.lowercase()}"
                    tx.isCardPayment -> "no: card bill payment"
                    Counting.refund(message) != null -> "refund: lowers spending"
                    else -> "yes"
                }
                val manual = ManualEntries.isManual(message)
                appendLine(
                    listOf(
                        DateTimeFormatter.ISO_LOCAL_DATE.format(at), DateTimeFormatter.ofPattern("HH:mm").format(at),
                        if (tx.isReceived) "credit" else "debit",
                        tx.amount, "INR", message.merchant.name, message.category.displayString,
                        tx.accountNumber.filter { it.isDigit() }.takeLast(4).let { if (it.isEmpty()) "" else "••$it" },
                        tx.bankName.displayString,
                        if (manual) "entered by hand" else if (tx.isCard) "card" else "account",
                        if (manual) "You" else message.message.sender,
                        counted,
                        samePaymentColumn(message, groups),
                        if (manual) message.message.body else "",
                        message.tags.joinToString(" "),
                        message.note.orEmpty()
                    ).joinToString(",") { csv(it) }
                )
            }
    }

    private fun samePaymentColumn(message: ClassifiedMessage, groups: PaymentGroups): String {
        val related = groups.followersOf(message.id).size
        if (related > 0) return "main, $related related"
        val link = message.samePayment ?: return ""
        return if (SamePayment.isRepeat(message)) "repeat of ${link.primaryLabel}" else "may repeat ${link.primaryLabel}"
    }

    /** A value safe for a spreadsheet: quoted when it has a comma, quote or line break, and never read as a formula. */
    private fun csv(value: String): String {
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@') && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
