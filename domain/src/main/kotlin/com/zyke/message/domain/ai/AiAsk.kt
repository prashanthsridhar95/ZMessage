package com.zyke.message.domain.ai

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.insights.DigestFormat
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What a question can ask for. This list is the whole vocabulary: the model picks from it, it cannot add to it. */
enum class AskMetric(val word: String, val title: String) {
    SPENT("spent", "Spent"),
    INCOME("income", "Received"),
    COUNT("count", "Payments"),
    BIGGEST("biggest", "Biggest payment"),
    AVERAGE("average", "Average payment")
}

enum class AskGroup(val word: String) { NONE("none"), CATEGORY("category"), PAYEE("payee"), MONTH("month") }

enum class AskPeriod(val word: String, val phrase: String) {
    TODAY("today", "today"),
    YESTERDAY("yesterday", "yesterday"),
    THIS_WEEK("this_week", "this week"),
    LAST_WEEK("last_week", "last week"),
    THIS_MONTH("this_month", "this month"),
    LAST_MONTH("last_month", "last month"),
    LAST_7_DAYS("last_7_days", "in the last 7 days"),
    LAST_30_DAYS("last_30_days", "in the last 30 days"),
    THIS_YEAR("this_year", "this year"),
    LAST_YEAR("last_year", "last year"),
    ALL_TIME("all_time", "in all the messages");

    /** The days this means, by the calendar (weeks start on Monday) and the person's own month. */
    fun resolve(today: LocalDate, cycle: CycleRule = CycleRule.CALENDAR): DateRange = when (this) {
        TODAY -> DateRange(today, today)
        YESTERDAY -> today.minusDays(1).let { DateRange(it, it) }
        THIS_WEEK -> DateRange(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
        LAST_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            .let { DateRange(it, it.plusDays(DAYS_IN_WEEK - 1)) }
        THIS_MONTH -> cycle.containing(today).let { DateRange(it.start, minOf(it.endInclusive, today)) }
        LAST_MONTH -> cycle.shifted(today, -1)
        LAST_7_DAYS -> DateRange(today.minusDays(DAYS_IN_WEEK - 1), today)
        LAST_30_DAYS -> DateRange(today.minusDays(THIRTY - 1), today)
        THIS_YEAR -> DateRange(today.withDayOfYear(1), today)
        LAST_YEAR -> DateRange(today.minusYears(1).withDayOfYear(1), today.withDayOfYear(1).minusDays(1))
        ALL_TIME -> DateRange(LocalDate.of(EARLIEST_YEAR, 1, 1), today)
    }

    companion object {
        fun of(word: String?): AskPeriod? = entries.firstOrNull { it.word.equals(word?.trim(), ignoreCase = true) }
        private const val DAYS_IN_WEEK = 7L
        private const val THIRTY = 30L
        private const val EARLIEST_YEAR = 2000
    }
}

/** A question as the app will run it: every part checked against the whitelists and the person's own data. */
data class AskQuery(
    val metric: AskMetric,
    val category: AiCategory?,
    /** A payee the person has, spelled as the app shows it. */
    val payee: String?,
    val period: AskPeriod,
    val group: AskGroup
)

object AskParser {
    private const val MAX_RATIO = 0.2
    private const val MIN_LENGTH = 3

    /**
     * @param knownPayees the names of the payees the person has, as the app shows them
     * @param customs the person's own category names (lower case) to their ids
     */
    fun parse(
        reply: String, question: String, knownPayees: Collection<String>, customs: Map<String, String> = emptyMap()
    ): Guarded<AskQuery> {
        val obj = MiniJson.firstObject(reply) ?: return Guarded.Rejected("The answer was not in a form the app can use")
        val metric = AskMetric.entries.firstOrNull { it.word.equals(obj.text("metric"), ignoreCase = true) }
            ?: return Guarded.Rejected("It asked for something the app cannot work out")
        val period = AskPeriod.of(obj.text("period")) ?: return Guarded.Rejected("I could not tell which days you meant")
        val group = AskGroup.entries.firstOrNull { it.word.equals(obj.text("group_by") ?: "none", ignoreCase = true) }
            ?: return Guarded.Rejected("It asked for a grouping the app does not have")
        val categoryText = obj.text("category")
        val category = AiSuggestionGuard.category(categoryText, customs)
        if (categoryText != null && !categoryText.equals("null", true) && !categoryText.equals("none", true) && category == null) {
            return Guarded.Rejected("There is no category called “${categoryText.take(MAX_SHOWN)}”")
        }
        val payeeText = obj.text("payee")?.takeUnless { it.equals("null", true) || it.equals("none", true) }
        val payee = if (payeeText == null) null else matchPayee(payeeText, question, knownPayees)
            ?: return Guarded.Rejected("I could not find a payee called “${payeeText.take(MAX_SHOWN)}”")
        return Guarded.Accepted(AskQuery(metric, category, payee, period, group))
    }

    /** The payee the person has that [claimed] means, only if the question really said it. Null when there is none. */
    fun matchPayee(claimed: String, question: String, known: Collection<String>): String? {
        val want = norm(claimed)
        if (want.length < MIN_LENGTH || want !in norm(question) && !nearIn(want, question)) return null
        known.firstOrNull { norm(it) == want }?.let { return it }
        known.filter { norm(it).length >= MIN_LENGTH && (norm(it).contains(want) || want.contains(norm(it))) }
            .minByOrNull { it.length }?.let { return it }
        return known.map { it to NearMatch.distance(norm(it), want).toDouble() / want.length }
            .filter { it.second <= MAX_RATIO }.minByOrNull { it.second }?.first
    }

    private fun nearIn(want: String, question: String): Boolean = NearMatch.find(question, want) != null

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private const val MAX_SHOWN = 30
}

data class AskRow(val label: String, val amount: BigDecimal, val count: Int)

/** What running a query on the person's messages came to. Every figure comes from [Counting], never from the model. */
data class AskResult(
    val query: AskQuery,
    val range: DateRange,
    /** The figure the metric asks for. */
    val figure: BigDecimal,
    val count: Int,
    val rows: List<AskRow>,
    /** The payments behind the figure: biggest first for "biggest", else newest first. */
    val matches: List<ClassifiedMessage>
)

object AskExecutor {
    const val MAX_MATCHES = 50
    const val MAX_ROWS = 8

    fun run(
        query: AskQuery, messages: List<ClassifiedMessage>, today: LocalDate, zone: ZoneId,
        cycle: CycleRule = CycleRule.CALENDAR, customNames: Map<String, String> = emptyMap()
    ): AskResult {
        val range = query.period.resolve(today, cycle)
        val inScope = messages.filter { m ->
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(m.message.date), zone)
            day in range && matchesCategory(m, query.category) &&
                (query.payee == null || m.merchant.name.equals(query.payee, ignoreCase = true))
        }
        val spends = inScope.mapNotNull { m -> Counting.spend(m)?.let { m to it } }
        val incomes = inScope.mapNotNull { m -> Counting.income(m)?.let { m to it } }
        val side = if (query.metric == AskMetric.INCOME) incomes else spends
        val total = if (query.metric == AskMetric.INCOME) incomes.sumOf { it.second } else Counting.netSpent(inScope)
        val figure = when (query.metric) {
            AskMetric.SPENT -> total
            AskMetric.INCOME -> total
            AskMetric.COUNT -> BigDecimal(side.size)
            AskMetric.BIGGEST -> side.maxOfOrNull { it.second } ?: BigDecimal.ZERO
            AskMetric.AVERAGE ->
                if (side.isEmpty()) BigDecimal.ZERO else side.sumOf { it.second }.divide(BigDecimal(side.size), 0, RoundingMode.HALF_UP)
        }
        val ordered = if (query.metric == AskMetric.BIGGEST) side.sortedByDescending { it.second }
        else side.sortedByDescending { it.first.message.date }
        val rows = if (query.group == AskGroup.NONE) emptyList() else
            side.groupBy { groupLabel(query.group, it.first, zone, customNames) }
                .map { (label, items) -> AskRow(label, items.sumOf { it.second }, items.size) }
                .sortedByDescending { it.amount }.take(MAX_ROWS)
        return AskResult(query, range, figure, side.size, rows, ordered.map { it.first }.take(MAX_MATCHES))
    }

    private fun matchesCategory(m: ClassifiedMessage, category: AiCategory?): Boolean = when {
        category == null -> true
        category.customId != null -> m.customCategoryId == category.customId
        else -> m.customCategoryId == null && m.category == category.builtIn
    }

    private fun groupLabel(group: AskGroup, m: ClassifiedMessage, zone: ZoneId, customNames: Map<String, String>): String = when (group) {
        AskGroup.CATEGORY -> m.customCategoryId?.let { customNames[it] } ?: m.category.displayString
        AskGroup.PAYEE -> m.merchant.name.ifBlank { m.message.sender }
        AskGroup.MONTH -> YearMonth.from(Instant.ofEpochMilli(m.message.date).atZone(zone)).let {
            it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + it.year
        }
        AskGroup.NONE -> ""
    }
}

/** The words of an answer, written by the app from an [AskResult]. */
data class AskText(
    /** A small heading in capitals-style, e.g. "Spent on Food, last week". */
    val title: String,
    /** The one big figure, already formatted; null when there is nothing to show. */
    val figure: String?,
    val sentence: String,
    /** How the question was understood, for the chips under the answer. */
    val understood: List<String>
)

object AskAnswer {
    private val DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun of(result: AskResult, zone: ZoneId = ZoneId.systemDefault()): AskText {
        val q = result.query
        val what = q.category?.name?.let { " on $it" }.orEmpty() + q.payee?.let { " at $it" }.orEmpty()
        val whenText = q.period.phrase
        val understood = listOfNotNull(
            q.metric.title, q.category?.name, q.payee, q.period.phrase.removePrefix("in ").removePrefix("the ")
        ).distinct()
        val title = q.metric.title + what + ", " + whenText
        val money = DigestFormat.rupees(result.figure)
        val n = result.count
        val payments = if (n == 1) "1 payment" else "$n payments"
        if (n == 0) return AskText(title, null, "No payments matched ${what.trim().ifEmpty { "your question" }} $whenText.", understood)
        val sentence = when (q.metric) {
            AskMetric.SPENT -> "You spent $money$what $whenText, across $payments."
            AskMetric.INCOME -> "You received $money$what $whenText, across $payments."
            AskMetric.COUNT -> "You made $payments$what $whenText."
            AskMetric.AVERAGE -> "Your average payment$what $whenText was $money, across $payments."
            AskMetric.BIGGEST -> {
                val top = result.matches.firstOrNull()
                val name = top?.merchant?.name?.takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty()
                val day = top?.let { " on " + DAY.format(Instant.ofEpochMilli(it.message.date).atZone(zone)) }.orEmpty()
                "Your biggest payment$what $whenText was $money$name$day."
            }
        }
        val figure = if (q.metric == AskMetric.COUNT) n.toString() else money
        val grouped = if (result.rows.isEmpty()) "" else
            " " + result.rows.take(GROUPS_IN_SENTENCE).joinToString("; ") { "${it.label} ${DigestFormat.rupees(it.amount)}" } + "."
        return AskText(title, figure, sentence + grouped, understood)
    }

    private const val GROUPS_IN_SENTENCE = 3
}
