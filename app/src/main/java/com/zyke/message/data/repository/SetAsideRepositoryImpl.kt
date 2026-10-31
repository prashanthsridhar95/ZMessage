package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.ledger.SetAsideRepository
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.ledger.TransferLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Payments left out of the totals and transfers between the person's own accounts, in private preferences. */
class SetAsideRepositoryImpl(context: Context) : SetAsideRepository {
    private val prefs = context.applicationContext.getSharedPreferences("set_aside", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    override val state: StateFlow<SetAsides> = _state.asStateFlow()

    override fun exclude(id: Long, excluded: Boolean) =
        write(_state.value.copy(excluded = if (excluded) _state.value.excluded + id else _state.value.excluded - id))

    override fun link(outId: Long?, inId: Long?) {
        if (outId == null && inId == null) return
        // A message is in one transfer at most: drop any earlier link that shares a leg with this one.
        val without = _state.value.transfers.filter { !(outId != null && it.outId == outId) && !(inId != null && it.inId == inId) }
        write(_state.value.copy(transfers = without + TransferLink(outId, inId)))
    }

    override fun unlink(id: Long) =
        write(_state.value.copy(transfers = _state.value.transfers.filter { it.outId != id && it.inId != id }))

    override fun dismissSuggestion(key: String) = write(_state.value.copy(dismissed = _state.value.dismissed + key))

    override fun restoreSuggestion(key: String) = write(_state.value.copy(dismissed = _state.value.dismissed - key))

    private fun write(state: SetAsides) {
        _state.value = state
        val transfers = JSONArray()
        state.transfers.forEach { transfers.put(JSONObject().put("out", it.outId ?: JSONObject.NULL).put("in", it.inId ?: JSONObject.NULL)) }
        prefs.edit()
            .putStringSet(EXCLUDED, state.excluded.mapTo(HashSet()) { it.toString() })
            .putString(TRANSFERS, transfers.toString())
            .putStringSet(DISMISSED, state.dismissed)
            .apply()
    }

    private fun load(): SetAsides = runCatching {
        val array = JSONArray(prefs.getString(TRANSFERS, "[]"))
        SetAsides(
            excluded = prefs.getStringSet(EXCLUDED, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
            transfers = (0 until array.length()).mapNotNull {
                val o = array.getJSONObject(it)
                val out = if (o.isNull("out")) null else o.getLong("out")
                val into = if (o.isNull("in")) null else o.getLong("in")
                if (out == null && into == null) null else TransferLink(out, into)
            },
            dismissed = prefs.getStringSet(DISMISSED, emptySet()).orEmpty().toSet()
        )
    }.getOrDefault(SetAsides())

    private companion object {
        const val EXCLUDED = "excluded"
        const val TRANSFERS = "transfers"
        const val DISMISSED = "dismissed"
    }
}
