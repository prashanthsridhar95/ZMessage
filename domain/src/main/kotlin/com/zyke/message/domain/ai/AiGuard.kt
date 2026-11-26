package com.zyke.message.domain.ai

import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal

/** What the model says a message is about. */
enum class ReadingKind(val word: String, val title: String, val movesMoney: Boolean) {
    SPEND("spend", "Looks like a payment", true),
    INCOME("income", "Looks like money received", true),
    REFUND("refund", "Looks like a refund", true),
    BILL("bill", "Looks like a bill", false),
    OTHER("other", "Does not look like a payment", false);

    companion object {
        fun of(word: String?): ReadingKind? = entries.firstOrNull { it.word.equals(word?.trim(), ignoreCase = true) }
    }
}

/** A category the model named: one of the app's built-in ones, or one the person made (by name; [customId] is its id). */
data class AiCategory(val builtIn: MessageCategory?, val customId: String? = null, val name: String)

/** The model's reading of one message, as it said it (not yet checked). */
data class RawReading(val kind: ReadingKind, val amount: String?, val merchant: String?, val last4: String?, val category: String?)

/** A reading that has been checked against the message: every piece is in the text, and [evidence] says where. */
data class CheckedReading(
    val kind: ReadingKind,
    /** Digits as the message writes them, without separators ("1250.00"). */
    val amount: String?,
    /** The merchant as the message writes it (never the model's spelling). */
    val merchant: String?,
    val last4: String?,
    val category: AiCategory?,
    val amountAt: IntRange?,
    val merchantAt: IntRange?,
    val last4At: IntRange?,
    /** The direction is the part the text cannot prove; true when the message has words for both ways. */
    val directionUnsure: Boolean
) {
    /** Where in the message each used piece is, for highlighting, in reading order. */
    val evidence: List<IntRange> get() = listOfNotNull(amountAt, merchantAt, last4At).sortedBy { it.first }
}

sealed interface Guarded<out T> {
    data class Accepted<T>(val value: T) : Guarded<T>
    data class Rejected(val reason: String) : Guarded<Nothing>
}

/**
 * Checks every claim the model makes against the one thing that is certain: the message text. A claim that the text does
 * not back is dropped or, where the claim is the whole point (an amount), the whole answer is refused. The guard decides;
 * the model never does.
 */
object AiSuggestionGuard {
    private const val MAX_AMOUNT = 1_000_000_000L
    private val DEBIT = Regex("(?i)\\b(debited|debit|spent|paid|payment of|purchase|withdrawn|sent|transferred to|deducted)\\b")
    private val CREDIT = Regex("(?i)\\b(credited|credit|received|deposited|deposit|refund|refunded|reversed|cashback|salary)\\b")

    /** Parses the reply strictly. Null when it is not one JSON object with a known kind. */
    fun parseReading(reply: String): RawReading? {
        val obj = MiniJson.firstObject(reply) ?: return null
        val kind = ReadingKind.of(obj.text("kind")) ?: return null
        return RawReading(kind, obj.number("amount"), obj.text("merchant"), obj.number("last4"), obj.text("category"))
    }

    /** [customs] maps a person's own category name (lower case) to its id. */
    fun check(raw: RawReading, body: String, customs: Map<String, String> = emptyMap()): Guarded<CheckedReading> {
        val found = findAmount(raw.amount, body)
        if (raw.kind.movesMoney && found == null) return Guarded.Rejected("The amount is not in the message")
        val merchant = raw.merchant?.let { NearMatch.find(body, it) }
        val last4 = raw.last4?.let { findLast4(it, body) }

        val hasDebit = DEBIT.containsMatchIn(body)
        val hasCredit = CREDIT.containsMatchIn(body)
        if (raw.kind == ReadingKind.SPEND && hasCredit && !hasDebit) return Guarded.Rejected("The message only talks about money coming in")
        if ((raw.kind == ReadingKind.INCOME || raw.kind == ReadingKind.REFUND) && hasDebit && !hasCredit) {
            return Guarded.Rejected("The message only talks about money going out")
        }
        return Guarded.Accepted(
            CheckedReading(
                raw.kind, found?.first, merchant?.first, last4?.first, category(raw.category, customs),
                found?.second, merchant?.second, last4?.second, directionUnsure = raw.kind.movesMoney && hasDebit && hasCredit
            )
        )
    }

    /** A category the app has: built-in by its name or its shown name, or the person's own. Anything else is null. */
    fun category(text: String?, customs: Map<String, String> = emptyMap()): AiCategory? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.equals("none", true) || t.equals("null", true)) return null
        customs[t.lowercase()]?.let { return AiCategory(null, it, t) }
        val built = MessageCategory.assignable.firstOrNull { it.displayString.equals(t, true) || it.name.equals(t, true) } ?: return null
        return AiCategory(built, null, built.displayString)
    }

    /** The amount as written (separators dropped) and where it sits in [body], when the message really contains it. */
    fun findAmount(claimed: String?, body: String): Pair<String, IntRange>? {
        val digits = claimed?.replace(",", "")?.replace("₹", "")?.trim().orEmpty()
        if (!Regex("\\d+(?:\\.\\d{1,2})?").matches(digits)) return null
        val value = digits.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0 || value > BigDecimal(MAX_AMOUNT)) return null
        for (m in NUMBER.findAll(body)) {
            val written = m.value.replace(",", "")
            if (written.toBigDecimalOrNull()?.compareTo(value) == 0) return written to m.range
        }
        return null
    }

    private fun findLast4(claimed: String, body: String): Pair<String, IntRange>? {
        val d = claimed.filter { it.isDigit() }
        if (d.length != 4) return null
        val run = Regex("\\d+").findAll(body).firstOrNull { it.value.endsWith(d) } ?: return null
        val end = run.range.last
        return d to (end - 3..end)
    }

    private val NUMBER = Regex("(?<![\\d.])\\d[\\d,]*(?:\\.\\d+)?")
}

/** Finds a name the model gave inside the text the name came from, allowing a small difference in spelling. */
object NearMatch {
    private const val MIN_LENGTH = 3
    private const val MAX_RATIO = 0.2

    /** The message's own spelling of [claimed] and its place in [body], or null when nothing in the message is close enough. */
    fun find(body: String, claimed: String): Pair<String, IntRange>? {
        val want = claimed.trim().replace(Regex("\\s+"), " ")
        if (want.length < MIN_LENGTH || want.equals("null", true)) return null
        val at = body.indexOf(want, ignoreCase = true)
        if (at >= 0) return body.substring(at, at + want.length) to (at until at + want.length)
        // Same words with other spacing or punctuation: compare letters and digits only.
        val wanted = want.lowercase().filter { it.isLetterOrDigit() }
        if (wanted.length < MIN_LENGTH) return null
        val words = Regex("\\S+").findAll(body).toList()
        val count = want.split(' ').size
        var best: Triple<Double, Int, Int>? = null
        for (i in words.indices) {
            for (n in maxOf(1, count - 1)..count + 1) {
                if (i + n > words.size) continue
                val window = words.subList(i, i + n).joinToString("") { it.value }.lowercase().filter { it.isLetterOrDigit() }
                val ratio = distance(window, wanted).toDouble() / wanted.length
                if (ratio <= MAX_RATIO && (best == null || ratio < best.first)) best = Triple(ratio, i, i + n - 1)
            }
        }
        val hit = best ?: return null
        val range = words[hit.second].range.first..words[hit.third].range.last
        return body.substring(range).trim { !it.isLetterOrDigit() } to range
    }

    internal fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/**
 * The rule that keeps the model away from money: any figure in a sentence it wrote must be one the code supplied. A number
 * is a run of digits (with separators and a decimal part); the sentence is refused if one is not in [allowed].
 */
object NumberGuard {
    private val NUMBER = Regex("\\d[\\d,]*(?:\\.\\d+)?")

    /** Every number in [text], separators dropped ("12,300.50" is "12300.50"). */
    fun numbersIn(text: String): List<String> = NUMBER.findAll(text).map { it.value.trimEnd(',', '.').replace(",", "") }.toList()

    /** [allowedTexts] are the strings the code supplied (an amount as shown, a percent, a count); their numbers are the whole allowance. */
    fun onlyAllowed(text: String, allowedTexts: Collection<String>): Boolean {
        val allowed = allowedTexts.flatMap { numbersIn(it) }.map(::canonical).toSet()
        return numbersIn(text).all { canonical(it) in allowed }
    }

    private fun canonical(number: String): String = number.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: number
}
