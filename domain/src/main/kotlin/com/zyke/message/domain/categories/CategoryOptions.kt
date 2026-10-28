package com.zyke.message.domain.categories

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageCategory.*
import com.zyke.message.domain.model.MessageContent

/** What a message is about, which decides the categories it makes sense to file it under. */
enum class MessageNature(val label: String) {
    INCOME("Money received"),
    EXPENSE("Money spent"),
    MANDATE("Autopay and upcoming debits"),
    CARD_BILL("Card bill"),
    FASTAG("FASTag"),
    COURIER("Delivery"),
    DATA("Data pack"),
    LOYALTY("Rewards"),
    LIFE("Orders, trips and appointments"),
    OTHER("Message")
}

/** The categories offered for a message: the ones that fit what it is, not all of them. */
object CategoryOptions {
    fun natureOf(content: MessageContent): MessageNature = when (content) {
        is MessageContent.Transaction -> if (content.isReceived) MessageNature.INCOME else MessageNature.EXPENSE
        is MessageContent.ScheduledPayment -> MessageNature.MANDATE
        is MessageContent.CardBill -> MessageNature.CARD_BILL
        is MessageContent.FastTag -> MessageNature.FASTAG
        is MessageContent.Courier -> MessageNature.COURIER
        is MessageContent.DataUsage -> MessageNature.DATA
        is MessageContent.Loyalty -> MessageNature.LOYALTY
        is MessageContent.Life -> MessageNature.LIFE
        else -> MessageNature.OTHER
    }

    private fun options(nature: MessageNature): List<MessageCategory> = when (nature) {
        MessageNature.INCOME -> listOf(SALARY, FAMILY, INVESTMENTS, SAVINGS, UPI_PAYMENT)
        MessageNature.EXPENSE -> listOf(
            FOOD, GROCERIES, SHOPPING, ENTERTAINMENT, TRAVEL, FUEL, UTILITIES, HEALTH, EDUCATION, FAMILY, CHARITY,
            INVESTMENTS, SAVINGS, INSURANCE, CREDIT_CARDS, PAY_LATER, NETWORK_CARRIERS, FASTAG, UPI_PAYMENT
        )
        MessageNature.MANDATE -> listOf(
            UTILITIES, INSURANCE, INVESTMENTS, SAVINGS, CREDIT_CARDS, PAY_LATER, ENTERTAINMENT, EDUCATION,
            NETWORK_CARRIERS, HEALTH, FAMILY, CHARITY
        )
        MessageNature.CARD_BILL -> listOf(CREDIT_CARDS, PAY_LATER)
        MessageNature.FASTAG -> listOf(FASTAG, TRAVEL, FUEL)
        MessageNature.COURIER -> listOf(COURIER, SHOPPING)
        MessageNature.DATA -> listOf(NETWORK_CARRIERS, UTILITIES)
        MessageNature.LOYALTY -> listOf(LOYALTY_PROGRAMMES, SHOPPING, TRAVEL)
        MessageNature.LIFE -> listOf(TRAVEL, ENTERTAINMENT, HEALTH, SHOPPING, EDUCATION, UTILITIES)
        // Anything unclear still gets a full choice, apart from Salary, which only makes sense for money received.
        MessageNature.OTHER -> MessageCategory.assignable.filter { it != SALARY }
    }

    /** The categories for [message], in a steady order; its current one is always among them so it can be seen and undone. */
    fun forMessage(message: ClassifiedMessage): List<MessageCategory> {
        val list = options(natureOf(message.content))
        return if (message.category in list || message.category == OTP || message.category == OTHERS) list else listOf(message.category) + list
    }
}
