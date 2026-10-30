package com.zyke.message.domain.classification

import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.life.LifeParser
import java.time.Instant
import java.time.ZoneId
import com.zyke.message.domain.model.BankTransaction
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CourierStatus
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
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
            NoticeParser.isProvidentFund(body) -> notice(message) ?: generic(message)
            GenericTransactionParser.mentionsMoney(body) -> bank(message)
            DATA_USAGE.matches(body) -> dataUsage(message)
            body.contains("Blue Dart") -> courier(message)
            body.contains("Petpooja") -> loyalty(message)
            else -> life(message) ?: generic(message)
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
        // A FASTag message the tag parser does not know (a parking charge, a registration) may still be a payment.
        val event = fastTagParser.parse(message.body) ?: return notice(message) ?: bank(message)
        return ClassifiedMessage(
            message = message,
            category = MessageCategory.FASTAG,
            merchant = merchants.resolve("Fastag", MessageCategory.FASTAG),
            content = MessageContent.FastTag(event)
        )
    }

    private fun bank(message: SmsMessage): ClassifiedMessage {
        val transaction = bankParser.parse(message.body)
        if (transaction == null || transaction.amount.isEmpty()) return notice(message) ?: life(message) ?: generic(message)

        return when (transaction.type) {
            BankTransactionType.CARD_BILL -> cardBill(message, transaction)
            BankTransactionType.UPCOMING_DEBIT, BankTransactionType.MANDATE_SET,
            BankTransactionType.MANDATE_CANCELLED, BankTransactionType.MANDATE_REQUEST -> scheduled(message, transaction)
            else -> transaction(message, transaction)
        }
    }

    /** A message whose transaction was read by something other than the rules (the on-device model). */
    fun fromTransaction(message: SmsMessage, tx: BankTransaction): ClassifiedMessage =
        transaction(message, tx).copy(isModelParsed = true)

    private fun transaction(message: SmsMessage, tx: BankTransaction): ClassifiedMessage {
        var merchant = nameIfMissing(merchants.resolve(tx.payee), tx)
        // A mutual-fund SIP is an investment whatever the fund is called.
        if (tx.bankName == BankName.MUTUAL_FUND && merchant.category == MessageCategory.OTHERS) {
            merchant = merchant.copy(category = MessageCategory.INVESTMENTS)
        }
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
                isCard = message.body.contains("Card", ignoreCase = true),
                isCardPayment = tx.type == BankTransactionType.RECEIVED && CARD_PAYMENT.containsMatchIn(message.body)
            )
        )
    }

    private fun scheduled(message: SmsMessage, tx: BankTransaction): ClassifiedMessage {
        val merchant = nameIfMissing(merchants.resolve(tx.payee.ifEmpty { tx.bankName.displayString }), tx)
        return ClassifiedMessage(
            message = message,
            category = merchant.category,
            merchant = merchant,
            content = MessageContent.ScheduledPayment(
                kind = scheduledKind(tx.type),
                bankName = tx.bankName,
                accountNumber = normalizeAccountNumber(tx.accountNumber),
                payee = tx.payee,
                amount = normalizeAmount(tx.amount),
                // A debit announced for "today" is due the day the message arrived.
                date = tx.dueDate?.takeIf { it != GenericTransactionParser.TODAY_MARKER }
                    ?: tx.dueDate?.let { java.time.Instant.ofEpochMilli(message.date).atZone(ZoneId.systemDefault()).toLocalDate() },
                isCard = message.body.contains("Card", ignoreCase = true)
            )
        )
    }

    /**
     * A row needs a name. When the message has no payee, or only an opaque id (a hash, a long reference),
     * say what kind of movement it was instead of leaving the title empty or showing the id.
     */
    private fun nameIfMissing(merchant: Merchant, tx: BankTransaction): Merchant {
        val name = merchant.name.trim()
        val opaque = name.isEmpty() || OPAQUE_ID.matches(name)
        if (!opaque) return merchant
        val received = tx.type == BankTransactionType.RECEIVED
        val label = when {
            merchant.logo == MerchantLogo.UPI || name.isNotEmpty() -> if (received) "UPI credit" else "UPI payment"
            tx.bankName.displayString.isNotEmpty() -> "${tx.bankName.displayString} ${if (received) "credit" else "debit"}"
            else -> if (received) "Money received" else "Payment"
        }
        return merchant.copy(name = label)
    }

    private fun scheduledKind(type: BankTransactionType) = when (type) {
        BankTransactionType.UPCOMING_DEBIT -> ScheduledKind.UPCOMING_DEBIT
        BankTransactionType.MANDATE_CANCELLED -> ScheduledKind.MANDATE_CANCELLED
        BankTransactionType.MANDATE_REQUEST -> ScheduledKind.MANDATE_REQUEST
        else -> ScheduledKind.MANDATE_SET
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

    /** Orders, trips, appointments and renewals: anything dated or tracked that is not a payment. */
    private fun life(message: SmsMessage): ClassifiedMessage? {
        val received = Instant.ofEpochMilli(message.date).atZone(ZoneId.systemDefault()).toLocalDate()
        val facts = LifeParser.parse(message.body, received) ?: return null
        val merchant = merchants.resolve("${message.sender} ${message.body}")
        val title = when {
            merchant.name != "${message.sender} ${message.body}" && merchant.logo != MerchantLogo.PERSON -> merchant.name
            else -> SenderKey.displayAddress(message.sender)
        }
        val category = if (facts.kind == LifeKind.TRAVEL) MessageCategory.TRAVEL else merchant.category
        return ClassifiedMessage(
            message = message,
            category = category,
            merchant = merchant.copy(name = title, category = category),
            content = MessageContent.Life(facts.kind, title, facts.status, facts.date, facts.time, facts.reference, facts.isDone)
        )
    }

    private fun notice(message: SmsMessage): ClassifiedMessage? {
        val (kind, amount) = NoticeParser.detect(message.body) ?: return null
        val merchant = merchants.resolve("${message.sender} ${message.body}")
        return ClassifiedMessage(
            message = message,
            category = if (kind == NoticeKind.PF_UPDATE) MessageCategory.SAVINGS else merchant.category,
            merchant = merchant,
            content = MessageContent.Notice(kind, amount)
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

    companion object {
        /**
         * Part of the cache's version tag. Raise it whenever the rules change what a message is read as, so what an
         * earlier build remembered is read again even when the app was swapped in without a fresh install.
         */
        const val RULES_VERSION = 12

        /** "Payment of Rs.5,000 received on your Credit Card XX1234", "credited to your card towards the due", ... */
        private val CARD_PAYMENT = Regex(
            "(?i)(?=.*\\bcard\\b)(?!.*\\brefund)(?:\\bpayment\\b|\\btowards\\b|thank you for (?:the |your )?payment|\\bdue\\b|\\boutstanding\\b)",
            RegexOption.DOT_MATCHES_ALL
        )
        const val MAX_PARSED_LENGTH = 4096

        /** Hashes and long references: 16+ letters and digits with no spaces, at least some digits. */
        val OPAQUE_ID = Regex("(?=.*\\d)[A-Za-z0-9]{16,}")

        val DATA_USAGE =
            """(\d+)% Alert: You have consumed \1% of your daily high Speed Data limit\. Track usage on Airtel Thanks App i\.airtel\.in/Get-Data""".toRegex()
        val LOYALTY =
            "Loyalty wallet for (.*?) is loaded with (\\d+\\.\\d{2}) points.*Current wallet balance: (\\d+\\.\\d{2})".toRegex()
    }
}
