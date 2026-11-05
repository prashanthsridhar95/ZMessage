package com.zyke.message.domain.insights

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.repository.RecurringState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How often a charge comes round, with what that makes of a year. */
enum class Cadence(val label: String, val perYear: Int, val maxGapDays: Long) {
    WEEKLY("Weekly", 52, 10),
    MONTHLY("Monthly", 12, 38),
    QUARTERLY("Every 3 months", 4, 100),
    YEARLY("Yearly", 1, 372);

    /** What [amount] charged on this cadence comes to over a year. */
    fun yearly(amount: BigDecimal): BigDecimal = amount.multiply(BigDecimal(perYear))

    /** The same as a monthly figure. */
    fun monthly(amount: BigDecimal): BigDecimal = yearly(amount).divide(BigDecimal(MONTHS), 2, RoundingMode.HALF_UP)

    companion object {
        private const val MONTHS = 12

        /** The cadence a typical gap between two charges points to; null when it fits none. */
        fun fromGapDays(days: Long): Cadence? = when (days) {
            in 5L..9L -> WEEKLY
            in 24L..38L -> MONTHLY
            in 80L..100L -> QUARTERLY
            in 350L..380L -> YEARLY
            else -> null
        }
    }
}

/** Where a subscription stands for the person. */
enum class SubscriptionState(val label: String) {
    ACTIVE("Active"),
    /** The person marked it cancelled. */
    CANCELLED("Cancelled"),
    /** Detected before, but the expected charge has not come for longer than its usual gap. */
    PAUSED("Paused")
}

/** The latest charge against the one before it. */
data class PriceChange(val from: BigDecimal, val to: BigDecimal) {
    val percent: Int get() = to.subtract(from).multiply(BigDecimal(100)).divide(from, 0, RoundingMode.HALF_UP).toInt()
}

enum class HintKind { PRICE_UP, NEW, SIMILAR }

/** A modest reason to take another look, phrased as a question. */
data class SubscriptionHint(val kind: HintKind, val text: String)

/** The kinds of service a small curated list can tell apart, for "two similar services". */
enum class ServiceKind(val label: String) {
    VIDEO("video streaming"), MUSIC("music"), NEWS("news"), CLOUD("cloud storage"), FITNESS("fitness"), FOOD_MEMBERSHIP("food delivery membership")
}

/** One row of the subscriptions page. */
data class SubscriptionLine(
    val subscription: Subscription,
    val state: SubscriptionState,
    /** What it costs over a year, and the same per month. */
    val yearly: BigDecimal,
    val monthly: BigDecimal,
    /** Set when the latest charge is more than 3% above the one before. */
    val priceChange: PriceChange?,
    val hints: List<SubscriptionHint>,
    val channel: PaymentChannel,
    /** The last four digits of the account or card it was paid from; empty when unknown. */
    val account: String,
    /** For the picture beside the name. */
    val merchant: Merchant?,
    /** The day the person marked it cancelled. */
    val cancelledOn: LocalDate?
) {
    val key: String get() = subscription.key
    val name: String get() = subscription.title

    /** The name with "#2 of 5" when the payee has several alike entries; for announcements and descriptions. */
    val label: String get() = if (subscription.suffix.isNotEmpty()) "$name ${subscription.suffix}" else name
    val cadence: Cadence get() = subscription.cadence
    val counts: Boolean get() = state == SubscriptionState.ACTIVE
}

/** How the page orders the rows it shows. */
enum class SubscriptionSort(val label: String) {
    YEARLY_COST("Yearly cost"),
    NEXT_CHARGE("Next charge"),
    NAME("Name")
}

/** One slice of the "where the money goes" bar: a subscription, or (with a null [line]) all the others together. */
data class CostShare(val line: SubscriptionLine?, val yearly: BigDecimal, val percent: Int)

/** All the subscriptions with what they cost in a year. Only the active ones are in the totals. */
data class SubscriptionOverview(val lines: List<SubscriptionLine>) {
    private val active: List<SubscriptionLine> get() = lines.filter { it.counts }

    val activeCount: Int get() = active.size
    val yearlyTotal: BigDecimal get() = active.fold(BigDecimal.ZERO) { sum, l -> sum + l.yearly }
    val monthlyTotal: BigDecimal get() = yearlyTotal.divide(BigDecimal(MONTHS), 0, RoundingMode.HALF_UP)
    val isEmpty: Boolean get() = lines.isEmpty()

    fun count(state: SubscriptionState): Int = lines.count { it.state == state }

    /** The rows for one [state] (all of them when null), in the order asked for; ties fall back to name. */
    fun view(state: SubscriptionState?, sort: SubscriptionSort): List<SubscriptionLine> {
        val shown = if (state == null) lines else lines.filter { it.state == state }
        val byName = compareBy<SubscriptionLine> { it.name.lowercase() }
        return when (sort) {
            SubscriptionSort.YEARLY_COST -> shown.sortedWith(compareByDescending<SubscriptionLine> { it.yearly }.then(byName))
            // Active ones come first, soonest charge first; paused and cancelled have no charge to look forward to.
            SubscriptionSort.NEXT_CHARGE -> shown.sortedWith(compareBy<SubscriptionLine> { !it.counts }.thenBy { it.subscription.nextDue }.then(byName))
            SubscriptionSort.NAME -> shown.sortedWith(byName)
        }
    }

    /** The [top] costliest active subscriptions and, when there are more, one slice for the rest; percents add to 100. */
    fun costShares(top: Int = 5): List<CostShare> {
        val total = yearlyTotal
        if (total.signum() <= 0) return emptyList()
        val ranked = active.sortedWith(compareByDescending<SubscriptionLine> { it.yearly }.thenBy { it.name.lowercase() })
        val named = ranked.take(top)
        val restYearly = ranked.drop(top).fold(BigDecimal.ZERO) { sum, l -> sum + l.yearly }
        fun percent(v: BigDecimal) = v.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP).toInt()
        val shares = named.map { CostShare(it, it.yearly, percent(it.yearly)) } +
            (if (restYearly.signum() > 0) listOf(CostShare(null, restYearly, percent(restYearly))) else emptyList())
        // Rounding can leave 99 or 101; the biggest slice takes the difference.
        val drift = 100 - shares.sumOf { it.percent }
        return if (drift == 0) shares else shares.mapIndexed { i, s -> if (i == 0) s.copy(percent = s.percent + drift) else s }
    }

    companion object {
        private const val MONTHS = 12
        /** A rise above this share of the earlier price is called out. */
        private val PRICE_RISE = BigDecimal("0.03")
        private const val NEW_WITHIN_DAYS = 60L
        /** The inbox has to reach back this far before "new" means anything: else it just began being read. */
        private const val HISTORY_NEEDED_DAYS = 120L

        fun priceChange(subscription: Subscription): PriceChange? {
            val before = subscription.previousAmount ?: return null
            if (before.signum() <= 0) return null
            val rise = subscription.amount.subtract(before)
            return if (rise > before.multiply(PRICE_RISE)) PriceChange(before, subscription.amount) else null
        }

        /**
         * @param subscriptions what [Subscriptions.find] found
         * @param states the person's confirm / ignore answers; an ignored one is not shown at all
         * @param cancelled the keys the person marked as cancelled, with the day
         * @param messages the live messages, to read the payment channel, the account and the picture from; payments the
         *   person set aside are never read
         */
        fun build(
            subscriptions: List<Subscription>,
            states: Map<String, RecurringState>,
            cancelled: Map<String, LocalDate>,
            today: LocalDate,
            messages: List<ClassifiedMessage> = emptyList(),
            zone: ZoneId = ZoneId.systemDefault()
        ): SubscriptionOverview {
            val shown = subscriptions.filter { states[it.key] != RecurringState.IGNORED }
            if (shown.isEmpty()) return SubscriptionOverview(emptyList())
            val context = context(shown, messages, zone)
            val historyStart = context.historyStart
            val kinds = shown.associate { it.key to ServiceKinds.of(it.payee) }

            val lines = shown.map { sub ->
                val cancelledOn = cancelled[sub.key]
                val state = when {
                    cancelledOn != null -> SubscriptionState.CANCELLED
                    ChronoUnit.DAYS.between(sub.lastPaid, today) > sub.cadence.maxGapDays -> SubscriptionState.PAUSED
                    else -> SubscriptionState.ACTIVE
                }
                val info = context.byKey[sub.payeeKey]
                SubscriptionLine(
                    subscription = sub,
                    state = state,
                    yearly = sub.cadence.yearly(sub.amount),
                    monthly = sub.cadence.monthly(sub.amount),
                    priceChange = priceChange(sub),
                    hints = emptyList(),
                    channel = info?.channel ?: PaymentChannel.DIRECT,
                    account = info?.account.orEmpty(),
                    merchant = info?.merchant,
                    cancelledOn = cancelledOn
                )
            }
            val withHints = lines.map { line ->
                if (!line.counts) line
                else line.copy(hints = hints(line, lines, kinds, today, historyStart))
            }
            val order = compareBy<SubscriptionLine> { rank(it.state) }
                .thenByDescending { it.yearly }
                .thenBy { it.name.lowercase() }
            return SubscriptionOverview(withHints.sortedWith(order))
        }

        /** Active ones first, then paused, then the cancelled; each by what it costs in a year. */
        private fun rank(state: SubscriptionState) = when (state) {
            SubscriptionState.ACTIVE -> 0
            SubscriptionState.PAUSED -> 1
            SubscriptionState.CANCELLED -> 2
        }

        private fun hints(
            line: SubscriptionLine, all: List<SubscriptionLine>, kinds: Map<String, ServiceKind?>, today: LocalDate, historyStart: LocalDate?
        ): List<SubscriptionHint> {
            val result = ArrayList<SubscriptionHint>(2)
            line.priceChange?.let {
                result += SubscriptionHint(
                    HintKind.PRICE_UP,
                    "The price went from ${PaceNudges.money(it.from)} to ${PaceNudges.money(it.to)}. Did you expect that?"
                )
            }
            val first = line.subscription.firstPaid
            if (first != null && historyStart != null && !first.isBefore(today.minusDays(NEW_WITHIN_DAYS)) &&
                !historyStart.isAfter(today.minusDays(HISTORY_NEEDED_DAYS))
            ) {
                val days = ChronoUnit.DAYS.between(first, today)
                result += SubscriptionHint(HintKind.NEW, "First charged about $days days ago. Did you mean to start it?")
            }
            val kind = kinds[line.key]
            if (kind != null) {
                val others = all.filter { it.subscription.payeeKey != line.subscription.payeeKey && it.counts && kinds[it.key] == kind }
                    .distinctBy { it.subscription.payeeKey }
                if (others.isNotEmpty()) {
                    result += SubscriptionHint(
                        HintKind.SIMILAR,
                        "You also pay for ${others.joinToString(", ") { it.name }}, another ${kind.label} service. Do you use both?"
                    )
                }
            }
            return result
        }

        private class Info(val channel: PaymentChannel, val account: String, val merchant: Merchant?)
        private class Context(val byKey: Map<String, Info>, val historyStart: LocalDate?)

        private fun context(shown: List<Subscription>, messages: List<ClassifiedMessage>, zone: ZoneId): Context {
            if (messages.isEmpty()) return Context(emptyMap(), null)
            val wanted = shown.associateBy { it.payeeKey }
            val perKey = HashMap<String, MutableList<ClassifiedMessage>>()
            var oldest = Long.MAX_VALUE
            for (m in messages) {
                if (m.message.date < oldest) oldest = m.message.date
                if (m.setAside != null || com.zyke.message.domain.ledger.SamePayment.isRepeat(m)) continue
                val payee = when (val c = m.content) {
                    is MessageContent.Transaction -> if (c.isReceived || c.isCardPayment) continue else c.payee
                    is MessageContent.ScheduledPayment -> c.payee
                    else -> continue
                }
                val key = Subscriptions.keyFor(payee)
                if (key in wanted) perKey.getOrPut(key) { ArrayList() } += m
            }
            val byKey = perKey.mapValues { (key, list) ->
                val newest = list.sortedByDescending { it.message.date }
                val recent = newest.take(CHANNEL_MESSAGES)
                val txs = newest.filter { it.content is MessageContent.Transaction }
                val latestTx = txs.firstOrNull()?.content as? MessageContent.Transaction
                val channel = PaymentChannels.infer(recent.map { it.message.body }, latestTx?.isCard == true)
                val account = latestTx?.accountNumber.orEmpty().filter { it.isDigit() }.takeLast(LAST_DIGITS)
                val own = txs.firstOrNull { it.id == wanted.getValue(key).messageId } ?: txs.firstOrNull()
                Info(channel, account, own?.merchant)
            }
            val historyStart = oldest.takeIf { it != Long.MAX_VALUE }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            return Context(byKey, historyStart)
        }

        private const val CHANNEL_MESSAGES = 4
        private const val LAST_DIGITS = 4
    }
}

/** A small curated map from a service's name to its kind. Anything it does not know is simply not compared. */
object ServiceKinds {
    private class Entry(val kind: ServiceKind, names: List<String>) {
        val patterns = names.map { Regex("\\b" + Regex.escape(it) + "\\b") }
    }

    private val entries = listOf(
        Entry(
            ServiceKind.VIDEO,
            listOf(
                "netflix", "prime video", "hotstar", "jiohotstar", "disney", "sony liv", "sonyliv", "zee5", "jiocinema", "apple tv",
                "mubi", "sun nxt", "sunnxt", "hoichoi", "lionsgate", "discovery+", "youtube premium"
            )
        ),
        Entry(
            ServiceKind.MUSIC,
            listOf("spotify", "gaana", "jiosaavn", "saavn", "wynk", "apple music", "youtube music", "amazon music", "hungama")
        ),
        Entry(
            ServiceKind.NEWS,
            listOf("times of india", "hindustan times", "economic times", "the hindu", "wall street journal", "new york times", "bloomberg", "the ken", "business standard")
        ),
        Entry(ServiceKind.CLOUD, listOf("google one", "icloud", "dropbox", "onedrive", "microsoft 365", "office 365")),
        Entry(ServiceKind.FITNESS, listOf("cult fit", "cultfit", "cult.fit", "healthifyme", "fitpass", "headspace", "strava", "curefit")),
        Entry(ServiceKind.FOOD_MEMBERSHIP, listOf("swiggy one", "zomato gold", "zomato pro", "bbstar", "eatclub"))
    )

    fun of(name: String): ServiceKind? {
        val lower = name.lowercase()
        return entries.firstOrNull { e -> e.patterns.any { it.containsMatchIn(lower) } }?.kind
    }
}
