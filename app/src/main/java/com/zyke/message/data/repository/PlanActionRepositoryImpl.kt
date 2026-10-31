package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.plan.PlanActionState
import com.zyke.message.domain.plan.PlanActions
import com.zyke.message.domain.plan.PlanHide
import com.zyke.message.domain.plan.PlanKind
import com.zyke.message.domain.repository.PlanActionRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What the person dismissed or snoozed in the plan section, kept in private preferences (and so in a backup). */
class PlanActionRepositoryImpl(context: Context) : PlanActionRepository {

    private val prefs = context.applicationContext.getSharedPreferences("plan_actions", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<PlanActionState> = _state.asStateFlow()

    override fun dismiss(key: String, label: String, kind: PlanKind) = write(PlanActions.dismiss(_state.value, key, label, kind))

    override fun snooze(key: String, label: String, kind: PlanKind, until: LocalDate, today: LocalDate) =
        write(PlanActions.snooze(_state.value, key, label, kind, until, today))

    override fun restore(key: String) = write(PlanActions.restore(_state.value, key))

    override fun clean(today: LocalDate) = write(PlanActions.clean(_state.value, today))

    private fun write(next: PlanActionState) {
        if (next === _state.value) return
        _state.value = next
        val array = JSONArray()
        next.hides.values.forEach {
            array.put(
                JSONObject().put("key", it.key).put("label", it.label).put("kind", it.kind.name)
                    .put("until", it.until?.toString() ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): PlanActionState = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return PlanActionState())
        val hides = (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            val kind = runCatching { PlanKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            val until = if (o.isNull("until")) null else runCatching { LocalDate.parse(o.getString("until")) }.getOrNull() ?: return@mapNotNull null
            PlanHide(o.getString("key"), o.optString("label"), kind, until)
        }
        PlanActionState(hides.associateBy { it.key })
    }.getOrDefault(PlanActionState())

    private companion object {
        const val KEY = "hides"
    }
}
