package com.zyke.message.domain.feed

import com.zyke.message.domain.model.MessageCategory

/** What a typed search means: filters it implies, and the words left to look for in the text. */
data class InterpretedQuery(
    val filter: MessageFilter,
    val text: String,
    /** e.g. "Fuel Spends · Last 7 days"; null when the query is plain words. */
    val note: String?
) {
    val hasFilters: Boolean get() = filter != MessageFilter.All
}

/**
 * Understands everyday search phrases such as "fuel this week", "unread bills" or "swiggy last 30 days":
 * category, type, status and time words become filters, and the rest is matched as text. Rules, not a model,
 * so it behaves the same on every phone and every time.
 */
object QueryInterpreter {

    private val CATEGORY_WORDS: Map<String, MessageCategory> = mapOf(
        "food" to MessageCategory.FOOD, "fuel" to MessageCategory.FUEL, "petrol" to MessageCategory.FUEL,
        "diesel" to MessageCategory.FUEL, "shopping" to MessageCategory.SHOPPING, "travel" to MessageCategory.TRAVEL,
        "groceries" to MessageCategory.GROCERIES, "grocery" to MessageCategory.GROCERIES,
        "health" to MessageCategory.HEALTH, "entertainment" to MessageCategory.ENTERTAINMENT,
        "utilities" to MessageCategory.UTILITIES, "investment" to MessageCategory.INVESTMENTS,
        "investments" to MessageCategory.INVESTMENTS, "savings" to MessageCategory.SAVINGS, "insurance" to MessageCategory.INSURANCE,
        "charity" to MessageCategory.CHARITY, "donation" to MessageCategory.CHARITY, "education" to MessageCategory.EDUCATION,
        "family" to MessageCategory.FAMILY, "salary" to MessageCategory.SALARY, "fastag" to MessageCategory.FASTAG, "courier" to MessageCategory.COURIER
    )
    private val SMART_WORDS: Map<String, SmartFilter> = mapOf(
        "otp" to SmartFilter.OTP, "otps" to SmartFilter.OTP, "bill" to SmartFilter.BILLS, "bills" to SmartFilter.BILLS,
        "money" to SmartFilter.MONEY, "payments" to SmartFilter.MONEY, "promo" to SmartFilter.PROMOS,
        "promos" to SmartFilter.PROMOS, "offers" to SmartFilter.PROMOS, "others" to SmartFilter.OTHERS,
        "archived" to SmartFilter.ARCHIVED,
        "orders" to SmartFilter.TRAVEL, "deliveries" to SmartFilter.TRAVEL, "delivery" to SmartFilter.TRAVEL,
        "trips" to SmartFilter.TRAVEL, "flights" to SmartFilter.TRAVEL, "tickets" to SmartFilter.TRAVEL,
        "appointments" to SmartFilter.REMINDERS, "renewals" to SmartFilter.REMINDERS, "reminders" to SmartFilter.REMINDERS
    )
    private val STATUS_WORDS = mapOf("unread" to StatusFilter.UNREAD, "pinned" to StatusFilter.PINNED)
    private val FILLER = setOf("show", "me", "my", "all", "the", "from", "in", "on", "of", "for", "messages", "message", "sms", "a", "an")

    fun interpret(query: String): InterpretedQuery {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var category: MessageCategory? = null
        var smart = SmartFilter.ALL
        var status = StatusFilter.ANY
        var window = TimeWindow.ANY
        val rest = mutableListOf<String>()

        var i = 0
        while (i < words.size) {
            val word = words[i]
            val next = words.getOrNull(i + 1)
            val third = words.getOrNull(i + 2)
            when {
                word == "today" -> window = TimeWindow.TODAY
                (word == "this" || word == "last") && next == "week" -> { window = TimeWindow.WEEK; i++ }
                word == "last" && next == "7" && third == "days" -> { window = TimeWindow.WEEK; i += 2 }
                (word == "this" || word == "last") && next == "month" -> { window = TimeWindow.MONTH; i++ }
                word == "last" && next == "30" && third == "days" -> { window = TimeWindow.MONTH; i += 2 }
                word in CATEGORY_WORDS && category == null -> category = CATEGORY_WORDS.getValue(word)
                word in SMART_WORDS && smart == SmartFilter.ALL -> smart = SMART_WORDS.getValue(word)
                word in STATUS_WORDS && status == StatusFilter.ANY -> status = STATUS_WORDS.getValue(word)
                else -> rest += word
            }
            i++
        }

        val filter = MessageFilter(smart = smart, category = category, status = status, window = window)
        val hasFilters = filter != MessageFilter.All
        // Without a filter the words are just text; filler is only dropped when there is something it was framing.
        val text = (if (hasFilters) rest.filter { it !in FILLER } else rest).joinToString(" ")
        val note = if (hasFilters) {
            listOfNotNull(
                category?.displayString, smart.takeIf { it != SmartFilter.ALL }?.label,
                status.takeIf { it != StatusFilter.ANY }?.label, window.takeIf { it != TimeWindow.ANY }?.label
            ).joinToString(" · ")
        } else {
            null
        }
        return InterpretedQuery(filter, text, note)
    }
}
