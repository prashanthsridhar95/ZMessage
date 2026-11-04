package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zyke.message.domain.investments.Compounding
import com.zyke.message.domain.investments.Holding
import com.zyke.message.domain.investments.HoldingRepository
import com.zyke.message.domain.investments.HoldingStatus
import com.zyke.message.domain.investments.HoldingStore
import com.zyke.message.domain.investments.Holdings
import com.zyke.message.domain.investments.InvestmentKind
import com.zyke.message.domain.investments.Payout
import com.zyke.message.domain.investments.Terms
import com.zyke.message.domain.investments.ValuePoint
import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * The investments the person entered, in private preferences (file "investments", so it travels with the backup). Versioned
 * JSON; reading is tolerant: a holding that cannot be read is skipped, an unknown field is ignored. The state changes
 * themselves are in [HoldingStore] (atomic, tested); this only loads and saves.
 */
class HoldingRepositoryImpl private constructor(
    private val prefs: SharedPreferences,
    private val store: HoldingStore
) : HoldingRepository by store {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("investments", Context.MODE_PRIVATE))

    private constructor(prefs: SharedPreferences) : this(prefs, HoldingStore(load(prefs)) { save(prefs, it) })

    private companion object {
        const val KEY = "state"
        const val VERSION = 1

        fun save(prefs: SharedPreferences, state: Holdings) {
            val root = JSONObject().put("v", VERSION)
                .put("holdings", JSONArray().also { a -> state.holdings.forEach { a.put(write(it)) } })
                .put("migrated", JSONArray(state.migratedItemIds.toList()))
                .put("dismissed", JSONArray(state.maturityDismissed.toList()))
            prefs.edit().putString(KEY, root.toString()).apply()
        }

        fun load(prefs: SharedPreferences): Holdings = runCatching {
            val root = JSONObject(prefs.getString(KEY, null) ?: return Holdings())
            val array = root.optJSONArray("holdings") ?: JSONArray()
            Holdings(
                holdings = (0 until array.length()).mapNotNull { runCatching { read(array.getJSONObject(it)) }.getOrNull() },
                migratedItemIds = strings(root.optJSONArray("migrated")),
                maturityDismissed = strings(root.optJSONArray("dismissed"))
            )
        }.getOrDefault(Holdings())

        fun strings(a: JSONArray?): Set<String> = if (a == null) emptySet() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet()

        fun JSONObject.str(name: String): String? = if (isNull(name) || !has(name)) null else optString(name).takeIf { it.isNotEmpty() }
        fun JSONObject.dec(name: String): BigDecimal? = str(name)?.toBigDecimalOrNull()
        fun JSONObject.date(name: String): LocalDate? = str(name)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        fun JSONObject.int(name: String): Int? = if (isNull(name) || !has(name)) null else optInt(name)

        fun write(h: Holding): JSONObject {
            val t = h.terms
            return JSONObject().put("id", h.id).put("kind", h.kind.name).put("name", h.name).put("institution", h.institution)
                .put("invested", h.invested.toPlainString()).put("current", h.currentValue?.toPlainString() ?: JSONObject.NULL)
                .put("asOf", h.valueAsOf?.toString() ?: JSONObject.NULL).put("start", h.startDate?.toString() ?: JSONObject.NULL)
                .put("notes", h.notes).put("receipt", h.receiptNo).put("status", h.status.name)
                .put("closedOn", h.closedOn?.toString() ?: JSONObject.NULL).put("closedAmount", h.closedAmount?.toPlainString() ?: JSONObject.NULL)
                .put("renewedFrom", h.renewedFrom ?: JSONObject.NULL)
                .put(
                    "terms",
                    JSONObject().put("rate", t.ratePercent.toPlainString()).put("compounding", t.compounding.name)
                        .put("tenure", t.tenureMonths ?: JSONObject.NULL).put("maturity", t.maturityDate?.toString() ?: JSONObject.NULL)
                        .put("given", t.maturityAmountGiven?.toPlainString() ?: JSONObject.NULL).put("payout", t.payout.name)
                        .put("installment", t.monthlyInstallment?.toPlainString() ?: JSONObject.NULL)
                        .put("day", t.installmentDay ?: JSONObject.NULL).put("paid", t.installmentsPaidOverride ?: JSONObject.NULL)
                        .put("leads", JSONArray(t.remindLeadDays.sortedDescending())).put("rdReminder", t.installmentReminder)
                )
                .put(
                    "history",
                    JSONArray().also { a ->
                        h.history.forEach { a.put(JSONObject().put("d", it.date.toString()).put("v", it.value.toPlainString()).put("i", it.invested.toPlainString())) }
                    }
                )
        }

        fun read(o: JSONObject): Holding {
            val t = o.optJSONObject("terms") ?: JSONObject()
            val leads = t.optJSONArray("leads")
            val terms = Terms(
                ratePercent = t.dec("rate") ?: BigDecimal.ZERO,
                compounding = runCatching { Compounding.valueOf(t.optString("compounding")) }.getOrDefault(Compounding.QUARTERLY),
                tenureMonths = t.int("tenure"), maturityDate = t.date("maturity"), maturityAmountGiven = t.dec("given"),
                payout = runCatching { Payout.valueOf(t.optString("payout")) }.getOrDefault(Payout.CUMULATIVE),
                monthlyInstallment = t.dec("installment"), installmentDay = t.int("day"), installmentsPaidOverride = t.int("paid"),
                remindLeadDays = if (leads == null) Terms.DEFAULT_LEADS else (0 until leads.length()).map { leads.optInt(it) }.toSet(),
                installmentReminder = t.optBoolean("rdReminder")
            )
            val history = o.optJSONArray("history")
            return Holding(
                id = o.getString("id"),
                kind = InvestmentKind.valueOf(o.getString("kind")),
                name = o.optString("name"), institution = o.optString("institution"),
                invested = o.dec("invested") ?: BigDecimal.ZERO, currentValue = o.dec("current"), valueAsOf = o.date("asOf"),
                startDate = o.date("start"), notes = o.optString("notes"), receiptNo = o.optString("receipt"),
                terms = terms,
                status = runCatching { HoldingStatus.valueOf(o.optString("status")) }.getOrDefault(HoldingStatus.ACTIVE),
                closedOn = o.date("closedOn"), closedAmount = o.dec("closedAmount"), renewedFrom = o.str("renewedFrom"),
                history = if (history == null) emptyList() else (0 until history.length()).mapNotNull {
                    val p = history.optJSONObject(it) ?: return@mapNotNull null
                    ValuePoint(p.date("d") ?: return@mapNotNull null, p.dec("v") ?: return@mapNotNull null, p.dec("i") ?: BigDecimal.ZERO)
                }
            )
        }
    }
}
