package com.zyke.message.domain.classification

import com.zyke.message.domain.model.BankTransaction
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CourierStatus
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.NetworkCarrier
import com.zyke.message.domain.model.SmsMessage

/**
 * Decides what kind of message an [SmsMessage] is and extracts its structured content.
 * Rules are evaluated in order; anything unrecognised ends up as [MessageContent.Generic].
 */
class MessageClassifier(
    private val merchants: MerchantResolver = MerchantResolver(),
    private val bankParser: BankMessageParser = BankMessageParser(),
    private val fastTagParser: FastTagMessageParser = FastTagMessageParser(),
    private val otpParser: OtpParser = OtpParser()
) {

    /**
     * Never throws: a message the parsers choke on is shown as a plain message rather than
     * taking the list down, and very long bodies are only parsed up to [MAX_PARSED_LENGTH]
     * characters so regex work stays bounded.
     */
    fun classify(message: SmsMessage): ClassifiedMessage {
        val input = if (message.body.length > MAX_PARSED_LENGTH) {
            message.copy(body = message.body.take(MAX_PARSED_LENGTH))
        } else {
            message
        }

        val result = try {
            classifyUnchecked(input)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            plain(input)
        }
        // Always hand back the original, untruncated message.
        return if (input === message) result else result.copy(message = message)
    }

    private fun plain(message: SmsMessage) = ClassifiedMessage(
        message = message,
        category = MessageCategory.OTHERS,
        merchant = Merchant(message.sender, MerchantLogo.PERSON, MessageCategory.OTHERS),
        content = MessageContent.Generic
    )

    private fun classifyUnchecked(message: SmsMessage): ClassifiedMessage {
        val body = message.body
        return when {
            OtpParser.isOtp(body) -> otp(message)
            body.contains("Bank Tag", true) || body.contains("Fastag", true) -> fastTag(message)
            GenericTransactionParser.mentionsMoney(body) -> bank(message)
            DATA_USAGE.matches(body) -> dataUsage(message)
            body.contains("Blue Dart") -> courier(message)
            body.contains("Petpooja") -> loyalty(message)
            else -> generic(message)
        }
    }

    private fun otp(message: SmsMessage): ClassifiedMessage {
        val parsed = otpParser.parse(message.body, message.sender)
        val lookup = "${parsed.senderName} ${message.body}"
        val merchant = merchants.resolve(lookup, MessageCategory.OTP)
        val name = if (merchant.name == lookup) parsed.senderName else merchant.name.ifEmpty { parsed.senderName }

        return ClassifiedMessage(
            message = message,
            category = MessageCategory.OTP,
            merchant = merchant.copy(name = name),
            content = MessageContent.Otp(
                code = parsed.code,
                senderName = name,
                paymentAmount = parsed.paymentAmount,
                cardNumber = parsed.cardNumber
            )
        )
    }

    private fun fastTag(message: SmsMessage): ClassifiedMessage {
        val event = fastTagParser.parse(message.body) ?: return generic(message)
        return ClassifiedMessage(
            message = message,
            category = MessageCategory.FASTAG,
            merchant = merchants.resolve("Fastag", MessageCategory.FASTAG),
            content = MessageContent.FastTag(event)
        )
    }

    private fun bank(message: SmsMessage): ClassifiedMessage {
        val transaction = bankParser.parse(message.body)
        if (transaction == null || transaction.amount.isEmpty()) return generic(message)

        return when (transaction.type) {
            BankTransactionType.CARD_BILL -> cardBill(message, transaction)
            BankTransactionType.UPCOMING_DEBIT, BankTransactionType.MANDATE_SET -> scheduled(message, transaction)
            else -> transaction(message, transaction)
        }
    }

    private fun transaction(message: SmsMessage, tx: BankTransaction): ClassifiedMessage {
        val merchant = merchants.resolve(tx.payee)
        return ClassifiedMessage(
            message = message,
            category = merchant.category,
            merchant = merchant,
            content = MessageContent.Transaction(
                bankName = tx.bankName,
                payee = tx.payee,
                amount = normalizeAmount(tx.amount),
                accountNumber = normalizeAccountNumber(tx.accountNumber),
                isReceived = tx.type == BankTransactionType.RECEIVED,
                isCard = message.body.contains("Card", ignoreCase = true)
            )
        )
    }

    private fun scheduled(message: SmsMessage, tx: BankTransaction): ClassifiedMessage {
        val merchant = merchants.resolve(tx.payee.ifEmpty { tx.bankName.displayString })
        return ClassifiedMessage(
            message = message,
            category = merchant.category,
            merchant = merchant,
            content = MessageContent.ScheduledPayment(
                kind = if (tx.type == BankTransactionType.UPCOMING_DEBIT) ScheduledKind.UPCOMING_DEBIT else ScheduledKind.MANDATE_SET,
                bankName = tx.bankName,
                accountNumber = normalizeAccountNumber(tx.accountNumber),
                payee = tx.payee,
                amount = normalizeAmount(tx.amount),
                date = tx.dueDate,
                isCard = message.body.contains("Card", ignoreCase = true)
            )
        )
    }

    private fun cardBill(message: SmsMessage, tx: BankTransaction): ClassifiedMessage {
        val merchant = merchants.resolve(tx.bankName.displayString)
        return ClassifiedMessage(
            message = message,
            category = merchant.category,
            merchant = merchant,
            content = MessageContent.CardBill(
                bankName = tx.bankName,
                accountNumber = normalizeAccountNumber(tx.accountNumber),
                amount = normalizeAmount(tx.amount),
                minimumDue = tx.minimumDue?.let(::normalizeAmount),
                dueDate = tx.dueDate,
                paymentLink = tx.paymentLink
            )
        )
    }

    private fun dataUsage(message: SmsMessage): ClassifiedMessage {
        val percent = DATA_USAGE.find(message.body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return ClassifiedMessage(
            message = message,
            category = MessageCategory.NETWORK_CARRIERS,
            merchant = merchants.resolve("Airtel", MessageCategory.NETWORK_CARRIERS),
            content = MessageContent.DataUsage(NetworkCarrier.AIRTEL, percent)
        )
    }

    private fun courier(message: SmsMessage): ClassifiedMessage {
        val body = message.body
        val status = when {
            body.contains("ARRIVING", true) && body.contains("TODAY", true) -> CourierStatus.ARRIVING_TODAY
            body.contains("Delivered", true) -> CourierStatus.DELIVERED
            else -> CourierStatus.UPDATE
        }
        return ClassifiedMessage(
            message = message,
            category = MessageCategory.COURIER,
            merchant = merchants.resolve("Blue dart", MessageCategory.COURIER),
            content = MessageContent.Courier(status)
        )
    }

    private fun loyalty(message: SmsMessage): ClassifiedMessage {
        val match = LOYALTY.find(message.body)
        if (!message.sender.contains("PPOOJA") || match == null) return generic(message)

        val (programme, points, balance) = match.destructured
        return ClassifiedMessage(
            message = message,
            category = MessageCategory.LOYALTY_PROGRAMMES,
            merchant = merchants.resolve(programme, MessageCategory.LOYALTY_PROGRAMMES),
            content = MessageContent.Loyalty(
                programme = programme,
                points = points,
                balance = balance,
                isLoaded = message.body.contains("loaded")
            )
        )
    }

    private fun generic(message: SmsMessage): ClassifiedMessage {
        val merchant: Merchant = merchants.resolve("${message.sender} ${message.body}")
        return ClassifiedMessage(
            message = message,
            category = merchant.category,
            merchant = merchant,
            content = MessageContent.Generic,
            isPromotional = PromoDetector.isPromotional(message.sender, message.body)
        )
    }

    private fun normalizeAmount(raw: String): String {
        val trimmed = if (raw.endsWith(".00") || raw.endsWith(".0")) raw.substringBefore('.') else raw
        return trimmed.trim().replace(",", "")
    }

    private fun normalizeAccountNumber(raw: String) =
        if (raw.startsWith("x")) raw.replace("x", "") else raw

    private companion object {
        const val MAX_PARSED_LENGTH = 4096

        val DATA_USAGE =
            """(\d+)% Alert: You have consumed \1% of your daily high Speed Data limit\. Track usage on Airtel Thanks App i\.airtel\.in/Get-Data""".toRegex()
        val LOYALTY =
            "Loyalty wallet for (.*?) is loaded with (\\d+\\.\\d{2}) points.*Current wallet balance: (\\d+\\.\\d{2})".toRegex()
    }
}
