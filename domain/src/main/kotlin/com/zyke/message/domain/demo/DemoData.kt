@file:Suppress(
    "MagicNumber", "LongMethod", "MaxLineLength", "TooManyFunctions", "LargeClass", "CyclomaticComplexMethod", "LongParameterList"
)

package com.zyke.message.domain.demo

import com.zyke.message.domain.calendar.CalendarEvent
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.investments.Compounding
import com.zyke.message.domain.investments.Holding
import com.zyke.message.domain.investments.InvestmentKind
import com.zyke.message.domain.investments.Terms
import com.zyke.message.domain.investments.ValuePoint
import com.zyke.message.domain.loans.Loan
import com.zyke.message.domain.loans.LoanKind
import com.zyke.message.domain.loans.Loans
import com.zyke.message.domain.markets.ChartRange
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.PriceHistory
import com.zyke.message.domain.markets.PricePoint
import com.zyke.message.domain.markets.Quote
import com.zyke.message.domain.markets.WatchSettings
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.domain.cards.CardNetwork
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.networth.NetWorthItem
import com.zyke.message.domain.networth.NetWorthKind
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.PlanItemKind
import com.zyke.message.domain.reminders.Reminder
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The made-up person behind Demo mode: "Asha Menon", three accounts, five months of messages and the setup a real
 * person would have (plan, budgets, goals, deposits). Everything here is invented: the banks are real brand names the
 * way a screenshot would show them, but every name, number, account ending and amount is fictional, and nothing is read
 * from the phone. Dated relative to [build]'s "now" so it always looks current, and deterministic: the same instant
 * and zone give the same data, byte for byte.
 *
 * The messages are plain SMS bodies in the wording real banks use, meant to be read by the real
 * [com.zyke.message.domain.classification.MessageClassifier]; nothing here pre-computes what the app shows.
 */
object DemoData {
    const val OWNER = "Asha Menon"

    /** The savings account the salary lands in, the second (secondary) savings account and the credit card. */
    const val MAIN_ACCOUNT = "4821"
    const val SECOND_ACCOUNT = "2096"
    const val CREDIT_CARD = "5580"

    /** Months of history, counting the current one. */
    const val MONTHS = 6

    fun build(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): DemoDataset = Builder(nowMillis, zone).build()

    /** A watch list for the market strip, and the prices to show in it (no network is used in Demo mode). */
    fun marketWatch(): WatchSettings = WatchSettings(
        enabled = true,
        symbols = listOf(
            WatchSymbol(MarketSymbols.NIFTY, "Nifty 50"),
            WatchSymbol(MarketSymbols.SENSEX, "Sensex"),
            WatchSymbol("RELIANCE.NS", "Reliance", "NSE"),
            WatchSymbol("TCS.NS", "TCS", "NSE"),
            WatchSymbol("INFY.NS", "Infosys", "NSE")
        ),
        showDollar = true,
        showGold = true
    )

    /** Static, plausible prices: a closing price and the day before's, per ticker. Never fetched, never updated. */
    fun marketQuotes(nowMillis: Long): Map<String, Quote> {
        fun quote(symbol: String, price: String, before: String, currency: String, exchange: String = "") =
            Quote(symbol, BigDecimal(price), BigDecimal(before), currency, nowMillis, exchange)
        return listOf(
            quote(MarketSymbols.NIFTY, "24865.40", "24790.15", "INR", "NSE"),
            quote(MarketSymbols.SENSEX, "81420.75", "81230.10", "INR", "BSE"),
            quote("RELIANCE.NS", "1432.60", "1441.20", "INR", "NSE"),
            quote("TCS.NS", "3318.00", "3296.45", "INR", "NSE"),
            quote("INFY.NS", "1584.30", "1587.90", "INR", "NSE"),
            quote(MarketSymbols.USD_INR, "87.42", "87.31", "INR"),
            quote(MarketSymbols.GOLD_USD_PER_OUNCE, "3342.50", "3331.80", "USD")
        ).associateBy { it.symbol }
    }

    /**
     * A believable chart for a ticker over a period, from its price now: a gentle, repeatable wander (seeded by the ticker and
     * period) that ends on [price]. For the stock sheet in Demo mode, where nothing is fetched.
     */
    fun priceHistory(symbol: String, range: ChartRange, price: Double, previous: Double?, nowMillis: Long): PriceHistory {
        val points = 60
        val spanSeconds = when (range) {
            ChartRange.DAY -> 6L * 3600
            ChartRange.WEEK -> 5L * 86_400
            ChartRange.MONTH -> 30L * 86_400
            ChartRange.QUARTER -> 90L * 86_400
            ChartRange.HALF_YEAR -> 182L * 86_400
            ChartRange.YEAR_TO_DATE -> 200L * 86_400
            ChartRange.YEAR -> 365L * 86_400
            ChartRange.FIVE_YEARS -> 1826L * 86_400
        }
        val drift = when (range) {
            ChartRange.DAY -> 0.004
            ChartRange.WEEK -> 0.012
            ChartRange.MONTH -> 0.03
            ChartRange.QUARTER -> 0.06
            ChartRange.HALF_YEAR -> 0.09
            ChartRange.YEAR_TO_DATE -> 0.08
            ChartRange.YEAR -> 0.14
            ChartRange.FIVE_YEARS -> 0.7
        }
        val seed = (symbol + range.label).hashCode().toLong() and 0x7fffffffL
        val end = nowMillis / 1000
        val start = price / (1.0 + drift)
        val list = (0 until points).map { i ->
            val t = i / (points - 1).toDouble()
            val wobble = Math.sin((i + seed % 7) * 0.9) * 0.006 + Math.sin((i + seed % 11) * 0.37) * 0.01
            val value = (start + (price - start) * t) * (1.0 + wobble * (1.0 - t))
            PricePoint(end - spanSeconds + (spanSeconds * i) / (points - 1), if (i == points - 1) price else value)
        }
        val values = list.map { it.value }
        return PriceHistory(
            symbol = symbol, range = range, name = MarketSymbols.defaultLabel(symbol), currency = "INR", exchange = "",
            points = list, price = price, previousClose = previous ?: price * 0.995,
            open = price * 0.998, dayHigh = price * 1.006, dayLow = price * 0.992, volume = 1_250_000L,
            high52 = (values.maxOrNull() ?: price) * 1.08, low52 = (values.minOrNull() ?: price) * 0.84
        )
    }

    // ---------------------------------------------------------------------------------------------------------------

    private class Raw(
        val at: Long,
        val sender: String,
        val body: String,
        val account: String? = null,
        val delta: BigDecimal? = null,
        val unread: Boolean = false
    )

    private class Builder(val now: Long, val zone: ZoneId) {
        val nowInstant: Instant = Instant.ofEpochMilli(now)
        val today: LocalDate = LocalDate.ofInstant(nowInstant, zone)
        val raws = ArrayList<Raw>()

        fun ms(date: LocalDate, hour: Int, minute: Int): Long = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

        /** The day [day] of the month [monthsBack] before this one (clamped to its length), or null when it is yet to come. */
        fun inMonth(monthsBack: Int, day: Int): LocalDate? {
            val ym = YearMonth.from(today).minusMonths(monthsBack.toLong())
            val date = ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))
            return date.takeIf { !it.isAfter(today) }
        }

        fun ago(days: Int): LocalDate = today.minusDays(days.toLong())

        /** A well-mixed, repeatable number for a text (String.hashCode alone moves too little between neighbours like "x#1" and "x#2"). */
        fun mix(text: String): Int {
            var h = text.hashCode()
            h = h xor (h ushr 16)
            h *= -2048144789
            h = h xor (h ushr 13)
            h *= -1028477387
            return h xor (h ushr 16)
        }

        /** Some amounts: the base moved a little, in a repeatable way, and rounded the way people pay. */
        fun amount(base: Int, key: String, month: Int, wide: Boolean = false): Int {
            val h = Math.floorMod(mix(key + "#" + month), 1000) / 1000.0
            val raw = if (wide) base * (0.45 + 1.3 * h) else base * (0.78 + 0.5 * h)
            val step = when {
                raw >= 5000 -> 100
                raw >= 800 -> 50
                raw >= 200 -> 10
                else -> 5
            }
            return ((raw / step).toInt() * step).coerceAtLeast(step)
        }

        fun day(key: String, month: Int, from: Int, to: Int): Int =
            from + Math.floorMod(mix(key + "@" + month), (to - from + 1))

        fun clock(key: String, month: Int): Pair<Int, Int> {
            val h = Math.floorMod(mix(key + "!" + month), 14 * 60)
            return (8 + h / 60) to (h % 60)
        }

        fun ref(key: String, month: Int): String =
            (400_000_000_000L + Math.floorMod(mix(key + "~" + month).toLong(), 90_000_000_000L)).toString()

        fun add(date: LocalDate?, hour: Int, minute: Int, sender: String, body: String, account: String? = null, delta: Int = 0, unread: Boolean = false) {
            if (date == null) return
            val at = ms(date, hour, minute)
            if (at > now) return
            raws += Raw(at, sender, body, account, if (account != null) BigDecimal(delta) else null, unread)
        }

        fun addAt(at: Long, sender: String, body: String, account: String? = null, delta: Int = 0, unread: Boolean = false) {
            if (at > now) return
            raws += Raw(at, sender, body, account, if (account != null) BigDecimal(delta) else null, unread)
        }

        val dmy: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yy")
        val dmyLong: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
        val slash: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")

        fun inr(n: Int): String = group(n.toLong()) + ".00"

        /** 1,23,456 the way Indian statements write it. */
        fun group(n: Long): String {
            val s = Math.abs(n).toString()
            if (s.length <= 3) return (if (n < 0) "-" else "") + s
            val head = s.dropLast(3)
            val parts = ArrayList<String>()
            var rest = head
            while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
            if (rest.isNotEmpty()) parts.add(0, rest)
            return (if (n < 0) "-" else "") + parts.joinToString(",") + "," + s.takeLast(3)
        }

        // --- the message shapes, in the wording each bank uses -------------------------------------------------------

        fun hdfcSent(date: LocalDate?, h: Int, mi: Int, payee: String, amt: Int, key: String, m: Int, unread: Boolean = false) {
            if (date == null) return
            add(date, h, mi, "VM-HDFCBK-S", "Sent Rs.$amt.00\nFrom HDFC Bank A/C x$MAIN_ACCOUNT\nTo $payee\nOn ${date.format(slash)}\nRef ${ref(key, m)}\nAvl bal: Rs.{BAL}",
                MAIN_ACCOUNT, -amt, unread)
        }

        fun hdfcCredit(date: LocalDate?, h: Int, mi: Int, vpa: String, amt: Int, key: String, m: Int, unread: Boolean = false) {
            if (date == null) return
            add(date, h, mi, "VM-HDFCBK-S", "Credit Alert!\nRs.$amt.00 credited to HDFC Bank A/c xx$MAIN_ACCOUNT on ${date.format(dmy)} from VPA $vpa (UPI ${ref(key, m)})\nAvl bal: Rs.{BAL}",
                MAIN_ACCOUNT, amt, unread)
        }

        fun cardSpend(date: LocalDate?, h: Int, mi: Int, merchant: String, amt: Int, unread: Boolean = false) {
            if (date == null) return
            add(date, h, mi, "VM-HDFCBK-S", "Rs.$amt.00 spent on HDFC Bank Credit Card XX$CREDIT_CARD at $merchant on ${date.format(dmy)}. Avl limit Rs ${group(100_000L + 1_000L * Math.floorMod(mix(merchant + amt), 40))}",
                null, 0, unread)
        }

        fun sbiUpi(date: LocalDate?, h: Int, mi: Int, payee: String, amt: Int, key: String, m: Int) {
            if (date == null) return
            val d = date.format(DateTimeFormatter.ofPattern("ddMMMyy", java.util.Locale.ENGLISH))
            add(date, h, mi, "JD-SBIUPI-S", "Dear Customer, A/C X$SECOND_ACCOUNT debited by Rs. $amt.00 on $d trf to $payee Refno ${ref(key, m)}. Avl Bal Rs {BAL}. If not you, call 1800111109 -SBI",
                SECOND_ACCOUNT, -amt)
        }

        fun build(): DemoDataset {
            val loanStart = YearMonth.from(today).minusMonths(16).atDay(7)
            val loan = Loan(
                id = "demo-loan", name = "Two-wheeler loan", kind = LoanKind.VEHICLE,
                principal = BigDecimal(150_000), annualRatePercent = BigDecimal("11.5"), tenureMonths = 36, startDate = loanStart, emiDay = 7
            )
            val emi = Loans.emi(loan.principal, loan.annualRatePercent, loan.tenureMonths).setScale(0, java.math.RoundingMode.HALF_UP).toInt()

            // The salary: the first of the month, a little more after the appraisal two months ago.
            for (m in 0 until MONTHS) {
                val salary = if (m <= 1) 88_000 else 82_000
                val date = inMonth(m, 1)
                add(date, 9, 12, "VM-HDFCBK-S",
                    "Rs.$salary.00 credited to HDFC Bank A/c XX$MAIN_ACCOUNT on ${date?.format(dmy)} by NEFT ZENITH LABS PVT LTD SALARY. Avl bal: Rs.{BAL}",
                    MAIN_ACCOUNT, salary)

                // Rent to the landlady, parents, the loan, four SIP mandates and the broadband.
                hdfcSent(inMonth(m, 2), 10, 5, "Sunita Rao", 22_000, "rent", m)
                hdfcSent(inMonth(m, 3), 11, 40, "Meena Menon", 8_000, "parents", m)
                val emiDate = inMonth(m, 7)
                add(emiDate, 6, 30, "VM-HDFCBK-S",
                    "NACH debit towards Bajaj Finserv Two Wheeler Loan EMI for INR ${inr(emi)} with UMRN HDFC0000112233 successfully processed in A/c no. XX$MAIN_ACCOUNT. Avl bal Rs.{BAL}",
                    MAIN_ACCOUNT, -emi)
                for (n in 1..4) {
                    add(inMonth(m, 5), 7, 30 + n, "VM-HDFCBK-S",
                        "NACH debit towards ICCL SIP for INR ${inr(2_500)} with UMRN HDFC00155200$n successfully processed in A/c no. XX$MAIN_ACCOUNT. Avl bal Rs.{BAL}",
                        MAIN_ACCOUNT, -2_500)
                }
                hdfcSent(inMonth(m, 20), 8, 5, "Airtel Prepaid", 299, "airtel", m)

                // Streaming and apps on the credit card.
                cardSpend(inMonth(m, 8), 0, 14, "NETFLIX", 649)
                cardSpend(inMonth(m, 12), 0, 20, "SPOTIFY", 119)
                cardSpend(inMonth(m, 15), 1, 5, "ICLOUD", 75)

                // Bills: electricity (paid each month but this one, which is due in a few days) and broadband (paid).
                if (m == 0) {
                    val billDate = ago(4)
                    add(billDate, 10, 0, "VM-BESCOM",
                        "Electricity charges of Rs.${amount(1_450, "bescom", m)}.00 for consumer 7741 is due on ${ago(-3).format(dmyLong)}. Pay on the BESCOM portal or by UPI. - BESCOM")
                } else {
                    val bill = amount(1_450, "bescom", m)
                    add(inMonth(m, 3), 10, 0, "VM-BESCOM", "Electricity charges of Rs.$bill.00 for consumer 7741 is due on ${inMonth(m, 10)?.format(dmyLong)}. Pay on the BESCOM portal or by UPI. - BESCOM")
                    hdfcSent(inMonth(m, 8), 19, 22, "BESCOM", bill, "bescom", m)
                }
                if (m == 0) {
                    add(ago(11), 9, 30, "VM-SKYLNK", "Bill Dated ${ago(11).format(dmy)} of Rs.1,179.00 is Due on ${ago(1).format(DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH))}. Pay by UPI to avoid late fee. Skylink Broadband")
                    hdfcSent(ago(2), 20, 15, "Skylink Broadband", 1_179, "skylink", m)
                } else {
                    add(inMonth(m, 5), 9, 30, "VM-SKYLNK", "Bill Dated ${inMonth(m, 5)?.format(dmy)} of Rs.1,179.00 is Due on ${inMonth(m, 18)?.format(DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH))}. Pay by UPI to avoid late fee. Skylink Broadband")
                    hdfcSent(inMonth(m, 16), 20, 15, "Skylink Broadband", 1_179, "skylink", m)
                }

                // Everyday spending: how often and how much, per place.
                for ((payee, spec) in everyday) {
                    val (perMonth, base) = spec
                    for (n in 0 until perMonth) {
                        val key = "$payee$n"
                        val (h, mi) = clock(key, m)
                        hdfcSent(inMonth(m, day(key, m, 1, 28)), h, mi, payee, amount(base, key, m, wide = true), key, m)
                    }
                }
                for ((merchant, spec) in onCard) {
                    val (perMonth, base) = spec
                    for (n in 0 until perMonth) {
                        val key = "$merchant$n"
                        val (h, mi) = clock(key, m)
                        cardSpend(inMonth(m, day(key, m, 1, 28)), h, mi, merchant, amount(base, key, m, wide = true))
                    }
                }
                for ((payee, spec) in onSbi) {
                    val (perMonth, base) = spec
                    for (n in 0 until perMonth) {
                        val key = "$payee$n"
                        val (h, mi) = clock(key, m)
                        sbiUpi(inMonth(m, day(key, m, 1, 28)), h, mi, payee, amount(base, key, m, wide = true), key, m)
                    }
                }
                // Cash from the ATM, and a friend or two settling up.
                val atmDate = inMonth(m, day("atm", m, 9, 24))
                add(atmDate, 17, 40, "JD-SBIATM-S", "Rs 3000 w/d from A/c XX$SECOND_ACCOUNT on ${atmDate?.format(dmy)} at SBI ATM. Avl Bal Rs {BAL}. -SBI", SECOND_ACCOUNT, -3_000)
                hdfcCredit(inMonth(m, day("rohan-in", m, 10, 26)), 21, 8, "rohan.iyer@okaxis", amount(900, "rohan-in", m), "rohan-in", m)
                hdfcSent(inMonth(m, day("rohan-out", m, 4, 20)), 22, 31, "Rohan Iyer", amount(650, "rohan-out", m), "rohan-out", m)
            }

            // One-offs through the months.
            hdfcSent(inMonth(3, 14), 12, 15, "Give India Foundation", 1_000, "charity", 3)
            cardSpend(inMonth(3, 18), 11, 12, "STAR HEALTH INSURANCE", 14_200)
            cardSpend(inMonth(2, 22), 10, 25, "MAKEMYTRIP FLIGHTS", 6_840)
            cardSpend(inMonth(2, 24), 18, 5, "IRCTC", 1_650)
            cardSpend(inMonth(1, 12), 15, 40, "DECATHLON", 3_290)
            cardSpend(inMonth(1, 26), 19, 10, "BOOKMYSHOW", 760)
            cardSpend(inMonth(4, 9), 13, 35, "CROMA", 8_990)
            add(inMonth(4, 30), 6, 0, "JD-SBIINB-S", "Your A/C X$SECOND_ACCOUNT has a credit for Interest of Rs 412.00 on ${inMonth(4, 30)?.format(dmy)}. Avl Bal Rs {BAL} -SBI", SECOND_ACCOUNT, 412)
            add(inMonth(1, 30), 6, 0, "JD-SBIINB-S", "Your A/C X$SECOND_ACCOUNT has a credit for Interest of Rs 436.00 on ${inMonth(1, 30)?.format(dmy)}. Avl Bal Rs {BAL} -SBI", SECOND_ACCOUNT, 436)

            // A refund: a returned order paid back to the card a week ago.
            val refundDay = ago(8)
            cardSpend(ago(15), 14, 2, "MYNTRA", 2_199)
            add(refundDay, 12, 30, "VM-HDFCBK-S",
                "Rs.2199.00 credited to HDFC Bank Credit Card XX$CREDIT_CARD on ${refundDay.format(dmy)} for refund from MYNTRA")

            // The card's statements: one a month, each paid from the savings account a couple of days before its due date,
            // and the newest one still to pay in a few days (the card on Home, and "Bills").
            for (k in 0..3) {
                val statement = ago(9).minusMonths(k.toLong())
                val due = statement.plusDays(14)
                val total = if (k == 0) 18_430 else amount(15_500, "statement", k)
                val min = (total * 5 / 100 / 10) * 10
                add(statement, 8, 0, "VM-HDFCBK-S",
                    "HDFC Bank Credit Card XX$CREDIT_CARD Statement: Total due amt: Rs.${inr(total)} Min due amt: Rs.${inr(min)} Due by:${due.format(dmyLong)} pay https://example.com/demo-pay",
                    null, 0)
                if (k > 0) {
                    val paid = due.minusDays(2)
                    add(paid, 10, 45, "VM-HDFCBK-S",
                        "Rs ${total}.00 debited from A/c XX$MAIN_ACCOUNT on ${paid.format(dmy)} towards HDFC Credit Card XX$CREDIT_CARD bill payment (UPI Ref ${ref("cc", k)}). Avl bal: Rs.{BAL}",
                        MAIN_ACCOUNT, -total)
                    add(paid, 10, 46, "VM-HDFCBK-S",
                        "Payment of Rs.${inr(total)} has been received on your HDFC Bank Credit Card XX$CREDIT_CARD on ${paid.format(dmy)}. Thank you for the payment.")
                }
            }

            // FASTag: tolls, a recharge, and a low balance note that is still standing.
            val vehicle = "KA05MX2468"
            for ((daysBack, toll, place) in listOf(Triple(46, 65, "Hosur Toll"), Triple(33, 110, "Nelamangala Toll"), Triple(21, 65, "Hosur Toll"), Triple(12, 140, "Devanahalli Toll"))) {
                val d = ago(daysBack)
                add(d, 7, 50, "VK-IDFCFB-S", "INR $toll toll paid from IDFC FIRST Bank Tag 7788 for vehicle no. $vehicle at $place on ${d.format(DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH))} 07:50. Avbl. Bal.: INR${140 + daysBack}")
            }
            val rechargeDay = ago(40)
            add(rechargeDay, 16, 28, "VK-IDFCFB-S", "Recharge successful: IDFC FIRST Bank FASTag on $vehicle credited with Rs.500.0 on ${rechargeDay.format(dmyLong)} 16:28. Avbl Bal: Rs.640.0")
            add(ago(2), 8, 5, "AD-FASTAG-S", "Your FASTag linked to vehicle $vehicle is low on balance. Recharge now to avoid a double toll.", unread = true)

            // Codes: one just now (still counting down), one from yesterday.
            addAt(now - 3 * 60_000L, "VM-HDFCBK-S", "482913 is the OTP to log in to HDFC Bank NetBanking. Valid for 10 minutes. Do not share it with anyone. -HDFC Bank", unread = true)
            add(ago(1), 20, 44, "VM-HDFCBK-S", "Your OTP is 654321 for INR 1,250.00 on your card ending $CREDIT_CARD at Amazon. Do not share it.")
            add(ago(5), 13, 2, "AX-SWIGGY-S", "Use OTP 7421 to log in to Swiggy. Do not share it with anyone.")

            // Messages that are not money: a parcel, data use, a promotion.
            add(ago(1), 15, 20, "VM-BLUDRT", "Your Blue Dart shipment 51234567890 is out for delivery today. Track on the Blue Dart app.", unread = true)
            add(ago(6), 9, 15, "AX-AIRTEL-S", "You have used 82% of your daily data quota. Recharge now for more data. -Airtel")
            add(ago(3), 11, 0, "VM-MYNTRA", "Flat 30% off on the End of Reason Sale! Shop now and save big. Offer valid till Sunday. T&C apply.")

            // Nearly-now messages, so Home has "today" in it: a morning coffee and a lunch order.
            hdfcSent(today, 8, 40, "Third Wave Coffee", 280, "today-coffee", 0)
            hdfcSent(today, 13, 5, "Swiggy", 395, "today-lunch", 0, unread = true)

            flightToday()
            trainToday()

            return finish(loan)
        }

        /**
         * A booking for a flight that leaves a little over two hours from now, so Home has a flight today. The text is written
         * in India time, whichever zone the phone is in, because the airports are Indian and the tracker reads their clocks.
         */
        private fun flightToday() {
            val india = ZoneId.of("Asia/Kolkata")
            val soon = nowInstant.plusSeconds(2 * 3600L + 10 * 60L).atZone(india).let { it.withMinute(it.minute / 5 * 5).withSecond(0).withNano(0) }
            // Late in the evening it still leaves "today" (India time), at the end of the day, so the card is there at any hour.
            val latest = nowInstant.atZone(india).withHour(23).withMinute(55).withSecond(0).withNano(0)
            val leaves = if (soon.isAfter(latest)) latest else soon
            val lands = leaves.plusMinutes(165)
            val clock = DateTimeFormatter.ofPattern("HH:mm")
            val day = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
            add(
                ago(4), 10, 15, "VM-INDIGO",
                "Booking confirmed. Your IndiGo flight 6E 2345 from Chennai (MAA) to Delhi (DEL) on ${leaves.format(day)} departs ${leaves.format(clock)}, " +
                    "arrives ${lands.format(clock)}. PNR: DEMO42. Web check-in opens 48 hrs before departure."
            )
        }

        /**
         * A booking for a train that leaves a little over an hour from now (India time, today), so Home has a train today next to the
         * flight. Made up, like everything here: the train, the PNR and the passenger are not real.
         */
        private fun trainToday() {
            val india = ZoneId.of("Asia/Kolkata")
            val soon = nowInstant.plusSeconds(75 * 60L).atZone(india).let { it.withMinute(it.minute / 5 * 5).withSecond(0).withNano(0) }
            val latest = nowInstant.atZone(india).withHour(23).withMinute(50).withSecond(0).withNano(0)
            val leaves = if (soon.isAfter(latest)) latest else soon
            val arrives = leaves.plusMinutes(300)
            val clock = DateTimeFormatter.ofPattern("HH:mm")
            val day = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
            add(
                ago(2), 18, 5, "VM-IRCTCS",
                "Booking confirmed. PNR: 4400000042. Train 12633 KANNIYAKUMARI EXP on ${leaves.format(day)}. Chennai Egmore (MS) to Tiruchirappalli (TPJ). " +
                    "Dep ${leaves.format(clock)} Arr ${arrives.format(clock)}. SL, S4 32 SU. -IRCTC"
            )
        }

        private val everyday: List<Pair<String, Pair<Int, Int>>> = listOf(
            "Swiggy" to (6 to 420), "Zomato" to (4 to 460), "Third Wave Coffee" to (4 to 300), "Blinkit" to (4 to 560),
            "BigBasket" to (2 to 1_700), "Nilgiris Fresh" to (3 to 900), "Uber" to (4 to 240), "Ola" to (2 to 190),
            "Apollo Pharmacy" to (1 to 640), "Nykaa" to (2 to 900)
        )
        private val onCard: List<Pair<String, Pair<Int, Int>>> = listOf(
            "AMAZON" to (2 to 1_800), "MYNTRA" to (1 to 1_900), "FLIPKART" to (1 to 1_300), "INDIAN OIL FUEL" to (2 to 1_500),
            "UBER INDIA" to (2 to 310), "ZOMATO" to (2 to 520)
        )
        private val onSbi: List<Pair<String, Pair<Int, Int>>> = listOf(
            "DMart Ready" to (1 to 2_300), "Raju Stores" to (4 to 340), "HP Petrol Pump" to (2 to 1_400)
        )

        private fun finish(loan: Loan): DemoDataset {
            // Oldest first; the balance each message states follows the money in and out, ending on fixed figures.
            val sorted = raws.sortedWith(compareBy<Raw> { it.at }.thenBy { it.body })
            val targets = mapOf(MAIN_ACCOUNT to 118_640L, SECOND_ACCOUNT to 31_420L)
            val start = targets.mapValues { (account, target) ->
                target - sorted.filter { it.account == account }.sumOf { it.delta!!.toLong() }
            }
            val running = start.toMutableMap()
            val messages = sorted.mapIndexed { index, raw ->
                var body = raw.body
                if (raw.account != null) {
                    val balance = running.getValue(raw.account) + raw.delta!!.toLong()
                    running[raw.account] = balance
                    body = body.replace("{BAL}", group(balance) + ".00")
                }
                SmsMessage(id = 1_000L + index, sender = raw.sender, body = body, date = raw.at, isRead = !raw.unread)
            }
            return DemoDataset(
                messages = messages,
                cards = cards(),
                plan = plan(),
                budgets = mapOf(
                    MessageCategory.FOOD to BigDecimal(9_000), MessageCategory.GROCERIES to BigDecimal(7_000),
                    MessageCategory.SHOPPING to BigDecimal(8_000), MessageCategory.TRAVEL to BigDecimal(4_500),
                    MessageCategory.FUEL to BigDecimal(3_500), MessageCategory.ENTERTAINMENT to BigDecimal(2_000)
                ),
                dailyBudgets = mapOf(MessageCategory.FOOD to BigDecimal(450)),
                goals = goals(),
                holdings = holdings(),
                netWorth = listOf(
                    NetWorthItem("demo-nw-cash", "Cash at home", NetWorthKind.CASH, BigDecimal(12_000), today.minusDays(20)),
                    NetWorthItem("demo-nw-car", "Hatchback (resale value)", NetWorthKind.OTHER_ASSET, BigDecimal(310_000), today.minusDays(60)),
                    NetWorthItem("demo-nw-deposit", "Security deposit with landlady", NetWorthKind.OTHER_ASSET, BigDecimal(66_000), today.minusDays(150)),
                    NetWorthItem("demo-nw-friend", "Owed to a friend", NetWorthKind.OTHER_DEBT, BigDecimal(15_000), today.minusDays(35))
                ),
                loans = listOf(loan),
                reminders = listOf(
                    Reminder("demo-r-insurance", "Bike insurance renewal", BigDecimal(2_150), today.plusDays(18), time = LocalTime.of(10, 0)),
                    Reminder("demo-r-gift", "Pay Kavya back for the gift", BigDecimal(1_200), today.plusDays(2))
                ),
                events = listOf(
                    CalendarEvent("demo-e-trip", "Goa trip with college friends", today.plusMonths(3).withDayOfMonth(12), note = "Flights are booked"),
                    CalendarEvent("demo-e-dentist", "Dentist check-up", today.plusDays(9), LocalTime.of(17, 30))
                ),
                categories = categories(),
                nicknames = mapOf(
                    CategoryKey.normalise("Sunita Rao") to "Rent (Sunita)",
                    CategoryKey.normalise("Meena Menon") to "Amma",
                    CategoryKey.normalise("ICCL SIP") to "Index fund SIP"
                ),
                watch = marketWatch(),
                quotes = marketQuotes(now)
            )
        }

        private fun categories(): Map<String, MessageCategory> = listOf(
            "Swiggy" to MessageCategory.FOOD, "Zomato" to MessageCategory.FOOD, "ZOMATO" to MessageCategory.FOOD,
            "Third Wave Coffee" to MessageCategory.FOOD, "Blinkit" to MessageCategory.GROCERIES, "BigBasket" to MessageCategory.GROCERIES,
            "Nilgiris Fresh" to MessageCategory.GROCERIES, "DMart Ready" to MessageCategory.GROCERIES, "Raju Stores" to MessageCategory.GROCERIES,
            "Uber" to MessageCategory.TRAVEL, "UBER INDIA" to MessageCategory.TRAVEL, "Ola" to MessageCategory.TRAVEL, "IRCTC" to MessageCategory.TRAVEL,
            "MAKEMYTRIP FLIGHTS" to MessageCategory.TRAVEL, "Apollo Pharmacy" to MessageCategory.HEALTH,
            "Nykaa" to MessageCategory.SHOPPING, "AMAZON" to MessageCategory.SHOPPING, "MYNTRA" to MessageCategory.SHOPPING,
            "FLIPKART" to MessageCategory.SHOPPING, "DECATHLON" to MessageCategory.SHOPPING, "CROMA" to MessageCategory.SHOPPING,
            "INDIAN OIL FUEL" to MessageCategory.FUEL, "HP Petrol Pump" to MessageCategory.FUEL,
            "NETFLIX" to MessageCategory.ENTERTAINMENT, "SPOTIFY" to MessageCategory.ENTERTAINMENT, "ICLOUD" to MessageCategory.ENTERTAINMENT,
            "BOOKMYSHOW" to MessageCategory.ENTERTAINMENT, "BESCOM" to MessageCategory.UTILITIES, "Skylink Broadband" to MessageCategory.UTILITIES,
            "Airtel Prepaid" to MessageCategory.UTILITIES, "Sunita Rao" to MessageCategory.UTILITIES, "Meena Menon" to MessageCategory.FAMILY,
            "Give India Foundation" to MessageCategory.CHARITY, "STAR HEALTH INSURANCE" to MessageCategory.INSURANCE, "ICCL SIP" to MessageCategory.INVESTMENTS
        ).associate { (payee, category) -> CategoryKey.normalise(payee) to category }

        private fun cards(): List<UserCard> = listOf(
            UserCard("demo-card-main", CardType.ACCOUNT, MAIN_ACCOUNT, "Salary account", bankName = "HDFC Bank", variant = "Savings"),
            UserCard("demo-card-sbi", CardType.ACCOUNT, SECOND_ACCOUNT, "Everyday savings", bankName = "SBI", variant = "Savings"),
            UserCard(
                "demo-card-credit", CardType.CREDIT, CREDIT_CARD, "HDFC Millennia", statementDay = ago(9).dayOfMonth, bankName = "HDFC Bank",
                network = CardNetwork.VISA, variant = "Millennia", dueDay = ago(9).plusDays(14).dayOfMonth, creditLimit = 150_000L
            )
        )

        private fun plan(): MoneyPlan = MoneyPlan(
            salary = BigDecimal(88_000), salaryDay = 1,
            items = listOf(
                PlanItem("demo-p-rent", PlanItemKind.OUTFLOW, "Rent", BigDecimal(22_000), 2),
                PlanItem("demo-p-parents", PlanItemKind.OUTFLOW, "Amma", BigDecimal(8_000), 3),
                PlanItem("demo-p-sip", PlanItemKind.INVESTMENT, "Index fund SIP", BigDecimal(10_000), 5)
            ),
            savingsGoal = BigDecimal(15_000), cardSpendLimit = BigDecimal(30_000)
        )

        private fun goals(): List<Goal> = listOf(
            Goal("demo-g-goa", "Goa trip", BigDecimal(60_000), BigDecimal(24_000), today.plusMonths(3).withDayOfMonth(1)),
            Goal("demo-g-fund", "Emergency fund", BigDecimal(300_000), BigDecimal(142_000), today.plusMonths(14).withDayOfMonth(1)),
            Goal("demo-g-laptop", "New laptop", BigDecimal(85_000), BigDecimal(38_500), today.plusMonths(6).withDayOfMonth(1))
        )

        private fun holdings(): List<Holding> {
            fun points(from: Int, start: Int, now: Int) = (0..5).map { i ->
                val d = today.minusMonths((5 - i).toLong() * 2 + 1)
                ValuePoint(d, BigDecimal(start + (now - start) * (i + 1) / 6), BigDecimal(from + (i * (from / 12))))
            }
            return listOf(
                Holding(
                    id = "demo-h-fd", kind = InvestmentKind.FIXED_DEPOSIT, name = "Fixed deposit", institution = "SBI",
                    invested = BigDecimal(100_000), startDate = today.minusMonths(8), notes = "Receipt no. 000000",
                    terms = Terms(ratePercent = BigDecimal("7.1"), compounding = Compounding.QUARTERLY, tenureMonths = 18)
                ),
                Holding(
                    id = "demo-h-rd", kind = InvestmentKind.RECURRING_DEPOSIT, name = "Recurring deposit", institution = "HDFC Bank",
                    startDate = today.minusMonths(9),
                    terms = Terms(
                        ratePercent = BigDecimal("6.8"), compounding = Compounding.QUARTERLY, tenureMonths = 24,
                        monthlyInstallment = BigDecimal(5_000), installmentDay = 10
                    )
                ),
                Holding(
                    id = "demo-h-mf", kind = InvestmentKind.MUTUAL_FUND, name = "Nifty 50 index fund", institution = "Demo AMC",
                    invested = BigDecimal(150_000), currentValue = BigDecimal(171_400), valueAsOf = today.minusDays(2), startDate = today.minusMonths(14),
                    history = points(150_000, 150_000, 171_400)
                ),
                Holding(
                    id = "demo-h-gold", kind = InvestmentKind.GOLD, name = "Digital gold", institution = "Demo wallet",
                    invested = BigDecimal(40_000), currentValue = BigDecimal(52_800), valueAsOf = today.minusDays(6), startDate = today.minusMonths(20)
                )
            )
        }
    }
}
