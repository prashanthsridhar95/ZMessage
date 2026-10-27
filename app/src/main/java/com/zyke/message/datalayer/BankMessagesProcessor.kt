package com.zyke.message.datalayer

import com.zyke.message.model.BankName
import com.zyke.message.model.BankTransactionType
import com.zyke.message.model.ZBankMessage

class BankMessagesProcessor {
    fun processMessage(message: String): ZBankMessage? {
        try {
            processCommonMessages(message)?.let { return it }

            return if (message.contains("Credit Alert!")) {
                processHDFCBankSavingsAccountReceivedMessages(message)
            } else {
                if (message.contains("HDFC Bank A/C")) {
                    processHDFCBankSavingsAccountMessages(message)
                } else if (message.contains("HDFC Bank Card") || message.contains(Regex("Credit Card XX(\\d+) Statement"))) {
                    processHDFCBankCreditCardMessages(message)
                } else if (message.contains("Axis Bank Credit Card")) {
                    processAxisBankCreditCardMessages(message)
                }
                else if (message.contains("Federal Bank")) {
                    processFederalBankMessages(message)
                } else if (message.contains("American Express", true) || message.contains(
                        "AMEX Card",
                        true
                    )
                ) {
                    processAmexTransactionMessages(message)
                } else if (message.contains("Lazypay", true)) {
                    processLazypayMessages(message)
                } else if (message.contains("Simpl", true)) {
                    processSimplMessages(message)
                } else if (message.contains("Petpooja", true)) {
                    processPetpoojaMessages(message)
                } else {
                    ZBankMessage(
                        BankName.OTHERS,
                        "",
                        "",
                        "",
                        BankTransactionType.SENT
                    )
                }
            }
        }
        catch (e: Exception)
        {
            return null
        }
    }

    private fun String.amountOf(pattern: String): String? =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(this)
            ?.groupValues?.get(1)

    /**
     * Messages that follow a fixed template across banks (autopay, SIPs, netbanking, ...).
     * Returns null when the message is not one of them so the bank specific parsers can run.
     */
    private fun processCommonMessages(message: String): ZBankMessage? {
        val flat = message.replace(Regex("\\s+"), " ").trim()

        // Mutual fund SIP processed
        Regex("""SIP in \d+ in (.+?) for Rs\.?\s?([\d,]+\.?\d*) is processed""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                return ZBankMessage(BankName.MUTUAL_FUND, "", it.groupValues[1], it.groupValues[2], BankTransactionType.SENT)
            }

        // Mutual fund SIP due reminder
        Regex("""SIP instalment .*? for Rs\.?\s?([\d,]+\.?\d*) is due""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                return ZBankMessage(BankName.MUTUAL_FUND, "", "SIP instalment", it.groupValues[1], BankTransactionType.CARD_BILL)
            }

        // AutoPay (E-mandate) Success!
        if (flat.contains("AutoPay (E-mandate) Success", true)) {
            val amount = flat.amountOf("""Txn Amt:\s?INR\s?([\d,]+\.?\d*)""") ?: return null
            val card = flat.amountOf("""Via:HDFC Bank CC (\d+)""") ?: ""
            val payee = message.lines().map { it.trim() }
                .firstOrNull { it.startsWith("For ", true) }?.removePrefix("For ")?.trim().orEmpty()
            return ZBankMessage(BankName.HDFC, card, payee, amount, BankTransactionType.SENT)
        }

        // AutoPay activation successful.
        if (flat.contains("AutoPay activation successful", true)) {
            val amount = flat.amountOf("""Txn amt:\s?INR\s?([\d,]+\.?\d*)""") ?: return null
            val merchant = flat.amountOf("""Merchant:\s?(.+?) Axis Bank""") ?: ""
            val card = flat.amountOf("""Card no\.:\s?XX(\d+)""") ?: ""
            return ZBankMessage(BankName.AXIS, card, merchant, amount, BankTransactionType.SENT)
        }

        // E-Mandate! Rs.X will be deducted
        Regex("""E-Mandate!\s*Rs\.?\s?([\d,]+\.?\d*) will be deducted""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                val payee = message.lines().map { l -> l.trim() }
                    .firstOrNull { l -> l.startsWith("For ", true) }?.removePrefix("For ")?.trim().orEmpty()
                return ZBankMessage(BankName.HDFC, "", payee, it.groupValues[1], BankTransactionType.CARD_BILL)
            }

        // PAYMENT ALERT! INR X deducted from HDFC Bank A/C No 1234 towards ...
        Regex("""INR\s?([\d,]+\.?\d*) deducted from HDFC Bank A/C No (\d+) towards (.+?)(?: UMRN|:|$)""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                return ZBankMessage(BankName.HDFC, it.groupValues[2], it.groupValues[3].trim(), it.groupValues[1], BankTransactionType.SENT)
            }

        // Payment Successful! Rs. X from A/c ****1234 to ... via HDFC Bank NetBanking
        Regex("""Payment Successful! Rs\.?\s?([\d,]+\.?\d*) from A/c \**(\d+) to (.+?) via HDFC Bank NetBanking""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                return ZBankMessage(BankName.HDFC, it.groupValues[2], it.groupValues[3], it.groupValues[1], BankTransactionType.SENT)
            }

        // UPI LITE top-up
        Regex("""UPI LITE Top-up amounting to Rs\.?\s?([\d,]+\.?\d*) has been successful""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                return ZBankMessage(BankName.HDFC, "", "UPI Lite top-up", it.groupValues[1], BankTransactionType.SENT)
            }

        // Indian Bank ATM withdrawal
        Regex("""Rs\.?\s?([\d,]+\.?\d*) w/d from A/c XX(\d+)""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                return ZBankMessage(BankName.INDIAN_BANK, it.groupValues[2], "Cash withdrawal", it.groupValues[1], BankTransactionType.SENT)
            }

        // Razorpay POS receipt: merchant on its own line followed by INRx
        if (flat.contains("Razorpay POS Receipt", true)) {
            val lines = message.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val amountLine = lines.indexOfFirst { it.startsWith("INR", true) }
            if (amountLine > 0) {
                val amount = lines[amountLine].removePrefix("INR").trim()
                return ZBankMessage(BankName.RAZORPAY, "", lines[amountLine - 1], amount, BankTransactionType.SENT)
            }
        }

        // Electricity bill
        Regex("""Electricity charges of Rs\.?\s?([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                return ZBankMessage(BankName.OTHERS, "", "Electricity", it.groupValues[1], BankTransactionType.CARD_BILL)
            }

        // Insurance premium
        Regex("""Premium Rs\.?\s?([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
            .find(flat)?.let {
                return ZBankMessage(BankName.OTHERS, "", "Insurance premium", it.groupValues[1], BankTransactionType.SENT)
            }

        return null
    }

    private fun processPetpoojaMessages(message: String): ZBankMessage {
        val regex = """eBill of Rs\.([\d,]+\.?\d*) at (.+?)\. (https?://\S+)""".toRegex()
        val matchResult = regex.find(message)
        val (billAmount, merchant, paymentLink) = matchResult!!.destructured

        return ZBankMessage(
            BankName.PETPOOJA,
            "",
            merchant,
            billAmount,
            BankTransactionType.SENT
        )
    }

    private fun processSimplMessages(message: String): ZBankMessage {
        val regex =
            """Simpl bill of Rs\.([\d,]+\.?\d*)""".toRegex()

        val matchResult = regex.find(message)

        val (amount) = matchResult!!.destructured

        return ZBankMessage(
            BankName.SIMPL,
            "",
            "",
            amount,
            BankTransactionType.CARD_BILL
        )
    }

    private fun processLazypayMessages(message: String): ZBankMessage {
        val regex =
            """Rs\. ([\d,]+\.?\d*) for txn (\w+) on (.+?) was successful.*?Pay by (\d{1,2}[a-z]{2} \w+, \d{4})""".toRegex()

        val matchResult = regex.find(message)

        val (amount, transactionId, merchant, dueDate) = matchResult!!.destructured

        return ZBankMessage(
            BankName.LAZYPAY,
            "",
            merchant,
            amount,
            BankTransactionType.SENT
        )
    }

    private fun processAmexTransactionMessages(message: String): ZBankMessage {
        val regex =
            """INR ([\d,]+\.?\d*) on your AMEX card \*\* (\d+) at (.+?) on (\d{1,2} \w+,\s*\d{4}) at (\d{1,2}:\d{2} [APM]{2})""".toRegex()

        val matchResult = regex.find(message)

        val (amount, accountNumber, merchant, date, time) = matchResult!!.destructured
        return ZBankMessage(
            bankName = BankName.AMERICAN_EXPRESS,
            accountNumber = accountNumber,
            payee = merchant,
            amount = amount,
            transactionType = BankTransactionType.SENT
        )
    }

    private fun processHDFCBankSavingsAccountReceivedMessages(message: String): ZBankMessage {
        val regex =
            """Credit Alert!\nRs\.([\d\.]+) credited to HDFC Bank A/c (xx\d+) on (\d{2}-\d{2}-\d{2}) from VPA (.+?) \((UPI \d+)\)""".toRegex()

        val matchResult = regex.find(message)

        return matchResult!!.let {
            ZBankMessage(
                BankName.HDFC,
                it.groupValues[2],
                it.groupValues[4],
                it.groupValues[1],
                BankTransactionType.RECEIVED
            )
        }
    }

    private fun processHDFCBankSavingsAccountMessages(message: String): ZBankMessage {
        val regex =
            """Sent Rs\.([\d\.]+)[\n\r]+From HDFC Bank A/C x(\d+)[\n\r]+To (.+)[\n\r]+On (\d{2}/\d{2}/\d{2})[\n\r]+Ref (\d+)""".toRegex()

        val matchResult = regex.find(message)

        return matchResult!!.let {
            ZBankMessage(
                BankName.HDFC,
                it.groupValues[2],
                it.groupValues[3],
                it.groupValues[1],
                BankTransactionType.SENT
            )
        }
    }

    private fun processHDFCBankCreditCardMessages(message: String): ZBankMessage {
        val regex =
            """Sent Rs\.([\d\.]+)[\n\r]+From HDFC Bank Card (\d+)[\n\r]+To (.+)[\n\r]+On (\d{2}/\d{2}/\d{2})[\n\r]+Ref (\d+)""".toRegex()

        val matchResult = regex.find(message)

        return if (matchResult != null) {
            matchResult.let {
                ZBankMessage(
                    BankName.HDFC,
                    it.groupValues[2],
                    it.groupValues[3],
                    it.groupValues[1],
                    BankTransactionType.SENT
                )
            }
        } else {
            val regex2 =
                """Rs\.(\d+(?:\.\d+)?)\s+spent on HDFC Bank Card\s+x(\d+)\s+at\s+(.+?)\son\s+(\d{4}-\d{2}-\d{2}:\d{2}:\d{2}:\d{2})""".toRegex()

            val matchResult2 = regex2.find(message)

            if (matchResult2 != null) {
                ZBankMessage(
                    BankName.HDFC,
                    matchResult2.groupValues[2],
                    matchResult2.groupValues[3],
                    matchResult2.groupValues[1],
                    BankTransactionType.SENT
                )
            } else {
                val regex3 =
                    """Txn Rs\.(\d+(?:\.\d+)?)\s+On\s+HDFC Bank Card\s+(\d+)\s+At\s+(.+?)\s+by UPI\s+(\d+)\s+On\s+(\d{2}-\d{2})""".toRegex()
                val matchResult3 = regex3.find(message)

                if (matchResult3 != null) {
                    ZBankMessage(
                        BankName.HDFC,
                        matchResult3.groupValues[2],
                        matchResult3.groupValues[3],
                        matchResult3.groupValues[1],
                        BankTransactionType.SENT
                    )
                } else {
                    val regex4 = """Amt Due Rs\.(\d+) on HDFC Bank Card X(\d+)""".toRegex()
                    val matchResult4 = regex4.find(message)

                    if (matchResult4 != null) {
                        ZBankMessage(
                            BankName.HDFC,
                            matchResult4!!.groupValues[2],
                            "",
                            matchResult4.groupValues[1],
                            BankTransactionType.CARD_BILL
                        )
                    } else {
                        val regex5 =
                            """Credit Card XX(\d+) Statement: Total due amt: Rs\.([\d,]+\.?\d*) Min due amt: Rs\.([\d,]+\.?\d*) Due by:(\d{2}-\d{2}-\d{4}).*?(https?://\S+)""".toRegex()

                        val matchResult5 = regex5.find(message)

                        val (cardNumber, totalDue, minDue, dueDate, statementLink) = matchResult5!!.destructured

                        ZBankMessage(
                            BankName.HDFC,
                            cardNumber,
                            "",
                            totalDue,
                            BankTransactionType.CARD_BILL
                        )
                    }
                }
            }
        }
    }

    private fun processAxisBankCreditCardMessages(message: String): ZBankMessage {
        val regex = """E-Statement of your Axis Bank Credit Card no\. XX(?<cardNumber>\d+) has been generated\. Total amount due: INR\s*Dr\. (?<totalAmount>\d+\.\d+)\. Minimum amt due: INR\s*Dr\. (?<minAmount>\d+\.\d+), Due date: (?<dueDate>\d{2}-\d{2}-\d{2})(?:\. Visit (?<link>https?://[^\s]+) to view / download\.)?""".toRegex()
        val matchResult = regex.find(message)

        if(matchResult != null)
        {
            return ZBankMessage(
                BankName.AXIS,
                matchResult.groups["cardNumber"]!!.value,
                "",
                matchResult.groups["totalAmount"]!!.value,
                BankTransactionType.CARD_BILL
            )
        }
        else
        {
            val regex = "Axis Bank Credit Card XX(?<cardNumber>\\d+) of INR (?<totalAmount>\\d+\\.\\d+) has been ".toRegex()
            val matches = regex.find(message)

            return matches?.let {
                ZBankMessage(
                    BankName.AXIS,
                    it.groups["cardNumber"]!!.value,
                    "",
                    it.groups["totalAmount"]!!.value,
                    BankTransactionType.CARD_BILL
                )
            } ?: ZBankMessage(
                BankName.AXIS,
                "",
                "",
                "",
                BankTransactionType.CARD_BILL
            )
        }
    }

    private fun processFederalBankMessages(message: String): ZBankMessage {
        val regex = "Rs\\s*(\\d+(?:\\.\\d{2})?)".toRegex()
        val upiPattern = "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})".toRegex()
        val matchResult = regex.find(message)
        val upiId = upiPattern.find(message)

        return matchResult?.let {
            ZBankMessage(
                BankName.FEDERAL_BANK,
                "",
                upiId?.groupValues?.get(1) ?: "",
                matchResult.groupValues[1],
                BankTransactionType.SENT
            )
        } ?: ZBankMessage(
            BankName.FEDERAL_BANK,
            "",
            "",
            "",
            BankTransactionType.SENT
        )
    }
}