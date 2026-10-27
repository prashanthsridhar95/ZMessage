package com.zyke.message.domain.plan

import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserCard
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** Something the messages show that the person has not told the app about yet, ready to add in one tap. */
sealed interface QuickAdd {
    /** Stable, so a "not now" can be remembered. */
    val key: String
    val title: String
    val detail: String

    data class Salary(val amount: BigDecimal, val day: Int, val payer: String) : QuickAdd {
        override val key = "quickadd-salary"
        override val title = "Salary"
        override val detail = "About ₹$amount around day $day, from $payer"
    }

    data class Item(val item: PlanItem) : QuickAdd {
        override val key = "quickadd-item-" + item.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        override val title = item.name
        override val detail = "${item.kind.label} · about ₹${item.amount}" + (item.dayOfMonth?.let { " around day $it" } ?: "")
    }

    data class Account(val card: UserCard) : QuickAdd {
        override val key = "quickadd-card-${card.type.name.lowercase()}-${card.last4}"
        override val title = card.label
        override val detail = card.type.label
    }
}

/** Finds the salary, regular outflows, investments, cards and accounts the messages show but the person has not entered. */
object QuickAdds {
    /** Everyday habits are not "regular outflows" even when they happen monthly. */
    private val HABITS = setOf(
        MessageCategory.FOOD, MessageCategory.GROCERIES, MessageCategory.FUEL, MessageCategory.SHOPPING,
        MessageCategory.COURIER, MessageCategory.TRAVEL, MessageCategory.FASTAG
    )
    private val SAVING_KINDS = setOf(MessageCategory.INVESTMENTS, MessageCategory.SAVINGS)
    private const val MIN_CARD_MESSAGES = 2

    fun find(
        messages: List<ClassifiedMessage>,
        plan: MoneyPlan,
        cards: List<UserCard>,
        dismissed: Set<String>,
        now: Instant,
        zone: ZoneId
    ): List<QuickAdd> {
        val found = ArrayList<QuickAdd>()

        if (plan.salary == null) {
            SalaryDetector.detect(messages, now, zone)?.let { found += QuickAdd.Salary(it.typical, it.typicalDay, it.payer) }
        }

        val byId = messages.associateBy { it.id }
        val known = plan.items.map { words(it.name) }
        Subscriptions.find(messages, now, zone).forEach { repeat ->
            val message = byId[repeat.messageId] ?: return@forEach
            val category = message.category
            val tx = message.content as? MessageContent.Transaction
            val saving = category in SAVING_KINDS || tx?.bankName == BankName.MUTUAL_FUND
            // A payment to a person or a charity every month is a regular outflow; a shop or an app is a subscription.
            if (!saving && category in HABITS) return@forEach
            if (known.any { it.isNotEmpty() && it.any { word -> word in words(repeat.payee) } }) return@forEach
            found += QuickAdd.Item(
                PlanItem(
                    id = "quick-" + repeat.payee.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
                    kind = if (saving) PlanItemKind.INVESTMENT else PlanItemKind.OUTFLOW,
                    name = repeat.payee.trim(),
                    amount = repeat.amount,
                    dayOfMonth = repeat.lastPaid.dayOfMonth
                )
            )
        }

        found += accounts(messages, cards)

        return found.filter { it.key !in dismissed }
    }

    private fun accounts(messages: List<ClassifiedMessage>, cards: List<UserCard>): List<QuickAdd> {
        val taken = cards.map { it.last4 }.toSet()
        return messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            val digits = tx.accountNumber.filter { it.isDigit() }.takeLast(4)
            if (digits.length < 4 || digits in taken) return@mapNotNull null
            Triple(digits, tx.isCard, tx.bankName.displayString)
        }.groupBy { it.first }.mapNotNull { (digits, rows) ->
            if (rows.size < MIN_CARD_MESSAGES) return@mapNotNull null
            val bank = rows.first().third
            val type = if (rows.any { it.second }) CardType.CREDIT else CardType.ACCOUNT
            QuickAdd.Account(UserCard(id = "quick-$digits", type = type, last4 = digits, name = "", bankName = bank))
        }.sortedBy { (it as QuickAdd.Account).card.last4 }
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
}
