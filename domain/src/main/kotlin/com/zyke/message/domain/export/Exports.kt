package com.zyke.message.domain.export

import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the person can take out of the app: their transactions as a spreadsheet. Message text is left out; only what was read from it is written. */
object Exports {
    private val HEADER = listOf(
        "date", "time", "direction", "amount", "currency", "who", "category", "account", "bank", "kind", "from", "counted_in_totals", "note"
    )

    fun transactionsCsv(messages: List<ClassifiedMessage>, zone: ZoneId): String = buildString {
        appendLine(HEADER.joinToString(","))
        messages.asSequence()
            .filter { it.content is MessageContent.Transaction }
            .sortedByDescending { it.message.date }
            .forEach { message ->
                val tx = message.content as MessageContent.Transaction
                val at = Instant.ofEpochMilli(message.message.date).atZone(zone)
                val counted = when {
                    message.setAside != null -> "no: ${message.setAside!!.label.lowercase()}"
                    tx.isCardPayment -> "no: card bill payment"
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
                        if (manual) message.message.body else ""
                    ).joinToString(",") { csv(it) }
                )
            }
    }

    /** A value safe for a spreadsheet: quoted when it has a comma, quote or line break, and never read as a formula. */
    private fun csv(value: String): String {
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@') && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
