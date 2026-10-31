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

    /** What to put aside each month, from what was actually kept in the last few months. */
    data class SavingsGoal(val amount: BigDecimal, override val detail: String) : QuickAdd {
        override val key = "quickadd-savings-goal"
        override val title = "Savings goal ₹$amount a month"
    }

    /** A monthly limit for a category, from what was spent in it in the last few months. */
    data class Limit(val category: MessageCategory, val monthly: BigDecimal) : QuickAdd {
        override val key = "quickadd-limit-${category.name.lowercase()}"
        override val title = "${category.displayString} limit ₹$monthly a month"
        override val detail = "About what you spent there in the last few months"
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
        zone: ZoneId,
        budgets: Map<MessageCategory, BigDecimal> = emptyMap()
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
        savingsGoal(messages, plan, now, zone)?.let { found += it }
        found += limits(messages, budgets, now, zone)

        return found.filter { it.key !in dismissed }
    }

    private val COMMITTED = setOf(
        MessageCategory.INVESTMENTS, MessageCategory.SAVINGS, MessageCategory.INSURANCE,
        MessageCategory.CHARITY, MessageCategory.CREDIT_CARDS, MessageCategory.OTHERS, MessageCategory.OTP
    )
    private const val MONTHS_LOOKED_AT = 3L
    private const val MIN_MONTHS = 2
    private const val MAX_LIMIT_SUGGESTIONS = 3
    private val ROUND_SAVINGS = BigDecimal(500)
    private val ROUND_LIMIT = BigDecimal(100)
    private val MIN_LIMIT = BigDecimal(500)
    private val FALLBACK_SHARE = BigDecimal("0.10")

    private fun completeMonths(now: Instant, zone: ZoneId): List<java.time.YearMonth> {
        val thisMonth = java.time.YearMonth.from(java.time.LocalDate.ofInstant(now, zone))
        return (1..MONTHS_LOOKED_AT).map { thisMonth.minusMonths(it) }
    }

    private fun month(message: ClassifiedMessage, zone: ZoneId) =
        java.time.YearMonth.from(Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate())

    /** The savings goal: what was kept on average in the last months, or a tenth of the salary when nothing was. */
    private fun savingsGoal(messages: List<ClassifiedMessage>, plan: MoneyPlan, now: Instant, zone: ZoneId): QuickAdd? {
        if (plan.savingsGoal != null) return null
        val months = completeMonths(now, zone)
        val kept = months.mapNotNull { m ->
            val moves = messages.filter { month(it, zone) == m }.mapNotNull { message ->
                val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
                if (message.setAside != null) return@mapNotNull null
                tx.amount.toBigDecimalOrNull()?.let { if (tx.isReceived) it else it.negate() }
            }
            if (moves.isEmpty()) null else moves.fold(BigDecimal.ZERO, BigDecimal::add)
        }
        if (kept.size >= MIN_MONTHS) {
            val average = kept.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(kept.size), 0, java.math.RoundingMode.FLOOR)
            val rounded = average.divide(ROUND_SAVINGS, 0, java.math.RoundingMode.FLOOR).multiply(ROUND_SAVINGS)
            if (rounded.signum() > 0) return QuickAdd.SavingsGoal(rounded, "You kept about ₹$average a month over the last ${kept.size} months")
        }
        val salary = plan.salary ?: SalaryDetector.detect(messages, now, zone)?.typical ?: return null
        val tenth = salary.multiply(FALLBACK_SHARE).divide(ROUND_SAVINGS, 0, java.math.RoundingMode.FLOOR).multiply(ROUND_SAVINGS)
        return tenth.takeIf { it.signum() > 0 }?.let { QuickAdd.SavingsGoal(it, "A tenth of your salary") }
    }

    /** Monthly limits for the categories that take the most, from what they took in the last months. */
    private fun limits(messages: List<ClassifiedMessage>, budgets: Map<MessageCategory, BigDecimal>, now: Instant, zone: ZoneId): List<QuickAdd> {
        val months = completeMonths(now, zone).toSet()
        val spend = HashMap<MessageCategory, HashMap<java.time.YearMonth, BigDecimal>>()
        messages.forEach { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@forEach
            if (tx.isReceived || message.setAside != null || message.category in COMMITTED || message.category in budgets) return@forEach
            val m = month(message, zone)
            if (m !in months) return@forEach
            val amount = tx.amount.toBigDecimalOrNull() ?: return@forEach
            val byMonth = spend.getOrPut(message.category) { HashMap() }
            byMonth[m] = (byMonth[m] ?: BigDecimal.ZERO) + amount
        }
        return spend.mapNotNull { (category, byMonth) ->
            if (byMonth.size < MIN_MONTHS) return@mapNotNull null
            val average = byMonth.values.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(byMonth.size), 0, java.math.RoundingMode.CEILING)
            val limit = average.divide(ROUND_LIMIT, 0, java.math.RoundingMode.CEILING).multiply(ROUND_LIMIT)
            if (limit < MIN_LIMIT) null else category to limit
        }.sortedByDescending { it.second }.take(MAX_LIMIT_SUGGESTIONS).map { QuickAdd.Limit(it.first, it.second) }
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
