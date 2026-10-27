package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.repository.GoalRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The person's savings goals, kept in private preferences. */
class GoalRepositoryImpl(context: Context) : GoalRepository {

    private val prefs = context.applicationContext.getSharedPreferences("goals", Context.MODE_PRIVATE)

    private val _goals = MutableStateFlow(load())
    override val goals: StateFlow<List<Goal>> = _goals.asStateFlow()

    override fun save(goal: Goal) {
        val exists = _goals.value.any { it.id == goal.id }
        write(if (exists) _goals.value.map { if (it.id == goal.id) goal else it } else _goals.value + goal)
    }

    override fun remove(id: String) = write(_goals.value.filter { it.id != id })

    private fun write(goals: List<Goal>) {
        _goals.value = goals
        val array = JSONArray()
        goals.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("target", it.target.toPlainString())
                    .put("saved", it.saved.toPlainString()).put("date", it.targetDate?.toString() ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<Goal> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            Goal(
                id = o.getString("id"),
                name = o.getString("name"),
                target = o.optString("target").toBigDecimalOrNull() ?: return@mapNotNull null,
                saved = o.optString("saved").toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                targetDate = if (o.isNull("date")) null else runCatching { LocalDate.parse(o.getString("date")) }.getOrNull()
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "goals"
    }
}
