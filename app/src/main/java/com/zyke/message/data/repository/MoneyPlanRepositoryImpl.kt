package com.zyke.message.data.repository

import com.zyke.message.domain.cycle.CycleMode
import android.content.Context
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.PlanItemKind
import com.zyke.message.domain.repository.MoneyPlanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The person's money plan (salary, regular outflows, investments, savings goal) in private preferences. */
class MoneyPlanRepositoryImpl(context: Context) : MoneyPlanRepository {

    private val prefs = context.applicationContext.getSharedPreferences("money_plan", Context.MODE_PRIVATE)

    private val _plan = MutableStateFlow(load())
    override val plan: StateFlow<MoneyPlan> = _plan.asStateFlow()

    override fun save(plan: MoneyPlan) {
        _plan.value = plan
        val items = JSONArray()
        plan.items.forEach {
            items.put(
                JSONObject().put("id", it.id).put("kind", it.kind.name).put("name", it.name)
                    .put("amount", it.amount.toPlainString()).put("day", it.dayOfMonth ?: JSONObject.NULL)
            )
        }
        val json = JSONObject()
            .put("salary", plan.salary?.toPlainString() ?: JSONObject.NULL)
            .put("salaryDay", plan.salaryDay ?: JSONObject.NULL)
            .put("savingsGoal", plan.savingsGoal?.toPlainString() ?: JSONObject.NULL)
            .put("cycleMode", plan.cycleMode.name)
            .put("cycleDay", plan.cycleDay ?: JSONObject.NULL)
            .put("items", items)
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    override fun clear() {
        _plan.value = MoneyPlan()
        prefs.edit().clear().apply()
    }

    private fun load(): MoneyPlan = runCatching {
        val json = JSONObject(prefs.getString(KEY, null) ?: return@runCatching MoneyPlan())
        val items = json.optJSONArray("items") ?: JSONArray()
        MoneyPlan(
            salary = json.optString("salary").toBigDecimalOrNull(),
            salaryDay = if (json.isNull("salaryDay")) null else json.optInt("salaryDay"),
            savingsGoal = json.optString("savingsGoal").toBigDecimalOrNull(),
            cycleMode = CycleMode.entries.firstOrNull { it.name == json.optString("cycleMode") } ?: CycleMode.SALARY,
            cycleDay = if (json.isNull("cycleDay")) null else json.optInt("cycleDay"),
            items = (0 until items.length()).mapNotNull {
                val o = items.getJSONObject(it)
                val kind = PlanItemKind.entries.firstOrNull { k -> k.name == o.optString("kind") } ?: return@mapNotNull null
                PlanItem(
                    id = o.getString("id"),
                    kind = kind,
                    name = o.optString("name"),
                    amount = o.optString("amount").toBigDecimalOrNull() ?: return@mapNotNull null,
                    dayOfMonth = if (o.isNull("day")) null else o.optInt("day")
                )
            }
        )
    }.getOrDefault(MoneyPlan())

    private companion object {
        const val KEY = "plan"
    }
}
