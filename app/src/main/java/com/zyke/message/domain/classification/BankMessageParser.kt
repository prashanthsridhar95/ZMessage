package com.zyke.message.domain.classification

import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.BankTransaction
import com.zyke.message.domain.model.BankTransactionType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Extracts a [BankTransaction] from a bank / card / pay-later SMS body. */
class BankMessageParser {

    /**
     * Returns null when the message looked like one we handle but could not be parsed.
     * Messages from unknown senders yield a [BankName.OTHERS] transaction with empty fields.
     */
    fun parse(message: String): BankTransaction? = try {
        when {
            message.contains("Credit Alert!") -> parseHdfcCredit(message)
            message.contains("HDFC Bank A/C") -> parseHdfcAccountDebit(message)
            message.contains("HDFC Bank Card") ||
                message.contains(HDFC_STATEMENT_HINT) -> parseHdfcCard(message)
            message.contains("Axis Bank Credit Card") -> parseAxis(message)
            message.contains("Federal Bank") -> parseFederal(message)
            message.contains("American Express", true) ||
                message.contains("AMEX Card", true) -> parseAmex(message)
            message.contains("Lazypay", true) -> parseLazypay(message)
            message.contains("Simpl", true) -> parseSimpl(message)
            message.contains("Petpooja", true) -> parsePetpooja(message)
            else -> BankTransaction(BankName.OTHERS, "", "", "", BankTransactionType.SENT)
        }
    } catch (e: Exception) {
        null
    }

    private fun parsePetpooja(message: String): BankTransaction {
        val (amount, merchant) = PETPOOJA.find(message)!!.destructured
        return BankTransaction(BankName.PETPOOJA, "", merchant, amount, BankTransactionType.SENT)
    }

    private fun parseSimpl(message: String): BankTransaction {
        val (amount) = SIMPL.find(message)!!.destructured
        return BankTransaction(BankName.SIMPL, "", "", amount, BankTransactionType.CARD_BILL)
    }

    private fun parseLazypay(message: String): BankTransaction {
        val (amount, _, merchant) = LAZYPAY.find(message)!!.destructured
        return BankTransaction(BankName.LAZYPAY, "", merchant, amount, BankTransactionType.SENT)
    }

    private fun parseAmex(message: String): BankTransaction {
        val (amount, accountNumber, merchant) = AMEX.find(message)!!.destructured
        return BankTransaction(
            BankName.AMERICAN_EXPRESS, accountNumber, merchant, amount, BankTransactionType.SENT
        )
    }

    private fun parseHdfcCredit(message: String): BankTransaction {
        val g = HDFC_CREDIT.find(message)!!.groupValues
        return BankTransaction(BankName.HDFC, g[2], g[4], g[1], BankTransactionType.RECEIVED)
    }

    private fun parseHdfcAccountDebit(message: String): BankTransaction {
        val g = HDFC_ACCOUNT_DEBIT.find(message)!!.groupValues
        return BankTransaction(BankName.HDFC, g[2], g[3], g[1], BankTransactionType.SENT)
    }

    private fun parseHdfcCard(message: String): BankTransaction {
        // Each pattern captures (amount, card number, payee) in groups 1..3.
        val spend = HDFC_CARD_SPEND_PATTERNS.firstNotNullOfOrNull { it.find(message) }
        if (spend != null) {
            val g = spend.groupValues
            return BankTransaction(BankName.HDFC, g[2], g[3], g[1], BankTransactionType.SENT)
        }

        HDFC_CARD_AMOUNT_DUE.find(message)?.let {
            return BankTransaction(
                BankName.HDFC, it.groupValues[2], "", it.groupValues[1], BankTransactionType.CARD_BILL
            )
        }

        val (cardNumber, totalDue, minDue, dueDate, link) = HDFC_CARD_STATEMENT.find(message)!!.destructured
        return BankTransaction(
            BankName.HDFC, cardNumber, "", totalDue, BankTransactionType.CARD_BILL,
            dueDate = parseDate(dueDate, "dd-MM-yyyy"), minimumDue = minDue, paymentLink = link
        )
    }

    private fun parseAxis(message: String): BankTransaction {
        val statement = AXIS_STATEMENT.find(message)
        if (statement != null) {
            return axisBill(
                statement.groups["cardNumber"]!!.value,
                statement.groups["totalAmount"]!!.value,
                dueDate = parseDate(statement.groups["dueDate"]!!.value, "dd-MM-yy"),
                minimumDue = statement.groups["minAmount"]!!.value,
                link = statement.groups["link"]?.value
            )
        }
        val generated = AXIS_GENERATED.find(message)
        return if (generated != null) {
            axisBill(generated.groups["cardNumber"]!!.value, generated.groups["totalAmount"]!!.value)
        } else {
            axisBill("", "")
        }
    }

    private fun axisBill(
        cardNumber: String,
        amount: String,
        dueDate: LocalDate? = null,
        minimumDue: String? = null,
        link: String? = null
    ) = BankTransaction(
        BankName.AXIS, cardNumber, "", amount, BankTransactionType.CARD_BILL,
        dueDate = dueDate, minimumDue = minimumDue, paymentLink = link
    )

    private fun parseDate(raw: String, pattern: String): LocalDate? =
        runCatching { LocalDate.parse(raw, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()

    private fun parseFederal(message: String): BankTransaction {
        val amount = FEDERAL_AMOUNT.find(message)?.groupValues?.get(1) ?: return BankTransaction(
            BankName.FEDERAL_BANK, "", "", "", BankTransactionType.SENT
        )
        val upiId = UPI_ID.find(message)?.groupValues?.get(1) ?: ""
        return BankTransaction(BankName.FEDERAL_BANK, "", upiId, amount, BankTransactionType.SENT)
    }

    private companion object {
        val HDFC_STATEMENT_HINT = Regex("Credit Card XX(\\d+) Statement")

        val PETPOOJA = """eBill of Rs\.([\d,]+\.?\d*) at (.+?)\. (https?://\S+)""".toRegex()
        val SIMPL = """Simpl bill of Rs\.([\d,]+\.?\d*)""".toRegex()
        val LAZYPAY =
            """Rs\. ([\d,]+\.?\d*) for txn (\w+) on (.+?) was successful.*?Pay by (\d{1,2}[a-z]{2} \w+, \d{4})""".toRegex()
        val AMEX =
            """INR ([\d,]+\.?\d*) on your AMEX card \*\* (\d+) at (.+?) on (\d{1,2} \w+,\s*\d{4}) at (\d{1,2}:\d{2} [APM]{2})""".toRegex()

        val HDFC_CREDIT =
            """Credit Alert!\nRs\.([\d\.]+) credited to HDFC Bank A/c (xx\d+) on (\d{2}-\d{2}-\d{2}) from VPA (.+?) \((UPI \d+)\)""".toRegex()
        val HDFC_ACCOUNT_DEBIT =
            """Sent Rs\.([\d\.]+)[\n\r]+From HDFC Bank A/C x(\d+)[\n\r]+To (.+)[\n\r]+On (\d{2}/\d{2}/\d{2})[\n\r]+Ref (\d+)""".toRegex()

        val HDFC_CARD_SPEND_PATTERNS = listOf(
            """Sent Rs\.([\d\.]+)[\n\r]+From HDFC Bank Card (\d+)[\n\r]+To (.+)[\n\r]+On (\d{2}/\d{2}/\d{2})[\n\r]+Ref (\d+)""",
            """Rs\.(\d+(?:\.\d+)?)\s+spent on HDFC Bank Card\s+x(\d+)\s+at\s+(.+?)\son\s+(\d{4}-\d{2}-\d{2}:\d{2}:\d{2}:\d{2})""",
            """Txn Rs\.(\d+(?:\.\d+)?)\s+On\s+HDFC Bank Card\s+(\d+)\s+At\s+(.+?)\s+by UPI\s+(\d+)\s+On\s+(\d{2}-\d{2})"""
        ).map { it.toRegex() }
        val HDFC_CARD_AMOUNT_DUE = """Amt Due Rs\.(\d+) on HDFC Bank Card X(\d+)""".toRegex()
        val HDFC_CARD_STATEMENT =
            """Credit Card XX(\d+) Statement: Total due amt: Rs\.([\d,]+\.?\d*) Min due amt: Rs\.([\d,]+\.?\d*) Due by:(\d{2}-\d{2}-\d{4}).*?(https?://\S+)""".toRegex()

        val AXIS_STATEMENT =
            """E-Statement of your Axis Bank Credit Card no\. XX(?<cardNumber>\d+) has been generated\. Total amount due: INR\s*Dr\. (?<totalAmount>\d+\.\d+)\. Minimum amt due: INR\s*Dr\. (?<minAmount>\d+\.\d+), Due date: (?<dueDate>\d{2}-\d{2}-\d{2})(?:\. Visit (?<link>https?://[^\s]+) to view / download\.)?""".toRegex()
        val AXIS_GENERATED =
            "Axis Bank Credit Card XX(?<cardNumber>\\d+) of INR (?<totalAmount>\\d+\\.\\d+) has been ".toRegex()

        val FEDERAL_AMOUNT = "Rs\\s*(\\d+(?:\\.\\d{2})?)".toRegex()
        val UPI_ID = "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})".toRegex()
    }
}
