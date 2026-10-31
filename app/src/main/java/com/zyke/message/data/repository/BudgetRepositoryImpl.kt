package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zyke.message.domain.dashboard.BudgetAdjustments
import com.zyke.message.domain.dashboard.BudgetMigration
import com.zyke.message.domain.dashboard.BudgetMove
import com.zyke.message.domain.dashboard.CarryOver
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.BudgetRepository
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's budgets, all in private preferences: monthly amounts in `budgets` (a key per category, as always), daily
 * ones in `daily_budgets`, and the carry-over and moves between budgets under one extra key of `budgets`. The old
 * `envelopes` file is read once and folded in (see [migrateEnvelopes]); it is never changed or deleted.
 */
class BudgetRepositoryImpl(context: Context) : BudgetRepository {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("budgets", Context.MODE_PRIVATE)
    private val dailyPrefs = appContext.getSharedPreferences("daily_budgets", Context.MODE_PRIVATE)

    private val _budgets = MutableStateFlow(load(prefs))
    override val budgets: StateFlow<Map<MessageCategory, BigDecimal>> = _budgets.asStateFlow()

    private val _daily = MutableStateFlow(load(dailyPrefs))
    override val dailyBudgets: StateFlow<Map<MessageCategory, BigDecimal>> = _daily.asStateFlow()

    private val _adjustments = MutableStateFlow(loadAdjustments())
    override val adjustments: StateFlow<BudgetAdjustments> = _adjustments.asStateFlow()

    init {
        migrateEnvelopes()
    }

    override fun setBudget(category: MessageCategory, amount: BigDecimal?) {
        write(prefs, category, amount)
        _budgets.value = if (amount == null) _budgets.value - category else _budgets.value + (category to amount)
    }

    override fun setDailyBudget(category: MessageCategory, amount: BigDecimal?) {
        write(dailyPrefs, category, amount)
        _daily.value = if (amount == null) _daily.value - category else _daily.value + (category to amount)
    }

    override fun saveAdjustments(adjustments: BudgetAdjustments) {
        _adjustments.value = adjustments
        prefs.edit().putString(ADJUSTMENTS, encode(adjustments)).apply()
    }

    override fun clearAll() {
        // The migration flag stays, so budgets that were cleared are not brought back from the old envelopes.
        prefs.edit().clear().putBoolean(MIGRATED, true).apply()
        dailyPrefs.edit().clear().apply()
        _budgets.value = emptyMap()
        _daily.value = emptyMap()
        _adjustments.value = BudgetAdjustments()
    }

    /**
     * Folds the old envelope plans into the budgets once. The latest cycle's amounts become monthly budgets where there
     * is none, and the carry-over choices and moves come across. Safe to run again: the flag stops it, and the result of
     * running twice would be the same anyway.
     */
    private fun migrateEnvelopes() {
        if (prefs.getBoolean(MIGRATED, false)) return
        runCatching {
            val old = appContext.getSharedPreferences("envelopes", Context.MODE_PRIVATE).getString("book", null)
            if (old != null) {
                val root = JSONObject(old)
                val assigned = HashMap<LocalDate, Map<MessageCategory, BigDecimal>>()
                val moves = HashMap<LocalDate, List<BudgetMove>>()
                root.optJSONObject("cycles")?.let { cycles ->
                    for (key in cycles.keys()) {
                        val start = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                        val cycle = cycles.optJSONObject(key) ?: continue
                        assigned[start] = cycle.optJSONObject("assigned")?.let { amounts ->
                            amounts.keys().asSequence().mapNotNull { name ->
                                val category = category(name) ?: return@mapNotNull null
                                val amount = amounts.optString(name).toBigDecimalOrNull() ?: return@mapNotNull null
                                category to amount
                            }.toMap()
                        }.orEmpty()
                        moves[start] = decodeMoves(cycle.optJSONArray("moves"))
                    }
                }
                val carry = root.optJSONObject("carry")?.let { obj ->
                    obj.keys().asSequence().mapNotNull { name ->
                        val category = category(name) ?: return@mapNotNull null
                        category to (runCatching { CarryOver.valueOf(obj.getString(name)) }.getOrNull() ?: return@mapNotNull null)
                    }.toMap()
                }.orEmpty()
                val result = BudgetMigration.fromEnvelopes(
                    _budgets.value, _adjustments.value, assigned,
                    BudgetAdjustments(carry = carry, moves = moves.filterValues { it.isNotEmpty() })
                )
                result.monthly.forEach { (category, amount) -> if (_budgets.value[category] != amount) setBudget(category, amount) }
                if (result.adjustments != _adjustments.value) saveAdjustments(result.adjustments)
            }
        }
        // Only now is it marked done; a failure above leaves the old file for the next start to try again.
        prefs.edit().putBoolean(MIGRATED, true).apply()
    }

    private fun write(store: SharedPreferences, category: MessageCategory, amount: BigDecimal?) {
        val editor = store.edit()
        if (amount == null) editor.remove(category.name) else editor.putString(category.name, amount.toPlainString())
        editor.apply()
    }

    private fun load(store: SharedPreferences): Map<MessageCategory, BigDecimal> =
        MessageCategory.entries.mapNotNull { category ->
            store.getString(category.name, null)?.toBigDecimalOrNull()?.let { category to it }
        }.toMap()

    private fun encode(a: BudgetAdjustments): String {
        val carry = JSONObject().also { o -> a.carry.forEach { (c, m) -> o.put(c.name, m.name) } }
        val from = JSONObject().also { o -> a.carryFrom.forEach { (c, d) -> o.put(c.name, d.toString()) } }
        val moves = JSONObject().also { o ->
            a.moves.forEach { (start, list) ->
                o.put(start.toString(), JSONArray().also { arr ->
                    list.forEach { arr.put(JSONObject().put("from", it.from.name).put("to", it.to.name).put("amount", it.amount.toPlainString())) }
                })
            }
        }
        return JSONObject().put("carry", carry).put("carryFrom", from).put("moves", moves).toString()
    }

    private fun loadAdjustments(): BudgetAdjustments = runCatching {
        val root = JSONObject(prefs.getString(ADJUSTMENTS, null) ?: return BudgetAdjustments())
        val carry = root.optJSONObject("carry")?.let { o ->
            o.keys().asSequence().mapNotNull { name ->
                val category = category(name) ?: return@mapNotNull null
                category to (runCatching { CarryOver.valueOf(o.getString(name)) }.getOrNull() ?: return@mapNotNull null)
            }.toMap()
        }.orEmpty()
        val from = root.optJSONObject("carryFrom")?.let { o ->
            o.keys().asSequence().mapNotNull { name ->
                val category = category(name) ?: return@mapNotNull null
                category to (runCatching { LocalDate.parse(o.getString(name)) }.getOrNull() ?: return@mapNotNull null)
            }.toMap()
        }.orEmpty()
        val moves = root.optJSONObject("moves")?.let { o ->
            o.keys().asSequence().mapNotNull { key ->
                val start = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
                start to decodeMoves(o.optJSONArray(key))
            }.toMap()
        }.orEmpty()
        BudgetAdjustments(carry, from, moves)
    }.getOrDefault(BudgetAdjustments())

    private fun decodeMoves(array: JSONArray?): List<BudgetMove> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull {
            val m = array.optJSONObject(it) ?: return@mapNotNull null
            BudgetMove(
                category(m.optString("from")) ?: return@mapNotNull null,
                category(m.optString("to")) ?: return@mapNotNull null,
                m.optString("amount").toBigDecimalOrNull() ?: return@mapNotNull null
            )
        }
    }

    private fun category(name: String): MessageCategory? = runCatching { MessageCategory.valueOf(name) }.getOrNull()

    private companion object {
        const val ADJUSTMENTS = "adjustments"
        const val MIGRATED = "migrated_envelopes"
    }
}
