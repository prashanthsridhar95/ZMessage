package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.goals.ContributionSource
import com.zyke.message.domain.goals.GoalContribution
import com.zyke.message.domain.goals.GoalContributionRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What the person has put towards each goal, kept in private preferences. */
class GoalContributionRepositoryImpl(context: Context) : GoalContributionRepository {

    private val prefs = context.applicationContext.getSharedPreferences("goal_contributions", Context.MODE_PRIVATE)

    private val _contributions = MutableStateFlow(load())
    override val contributions: StateFlow<List<GoalContribution>> = _contributions.asStateFlow()

    override fun save(contribution: GoalContribution) {
        val exists = _contributions.value.any { it.id == contribution.id }
        write(if (exists) _contributions.value.map { if (it.id == contribution.id) contribution else it } else _contributions.value + contribution)
    }

    override fun remove(id: String) = write(_contributions.value.filter { it.id != id })

    private fun write(list: List<GoalContribution>) {
        _contributions.value = list
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().put("id", it.id).put("goal", it.goalId).put("amount", it.amount.toPlainString())
                    .put("date", it.date.toString()).put("source", it.source.name).put("message", it.messageId ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<GoalContribution> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            GoalContribution(
                id = o.getString("id"),
                goalId = o.getString("goal"),
                amount = o.optString("amount").toBigDecimalOrNull() ?: return@mapNotNull null,
                date = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull() ?: return@mapNotNull null,
                source = runCatching { ContributionSource.valueOf(o.getString("source")) }.getOrDefault(ContributionSource.MANUAL),
                messageId = if (o.isNull("message")) null else o.getLong("message")
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "contributions"
    }
}
