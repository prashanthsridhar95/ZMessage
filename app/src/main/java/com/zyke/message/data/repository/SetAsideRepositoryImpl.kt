package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.ledger.SetAsideRepository
import com.zyke.message.domain.ledger.SetAsideStore
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.ledger.TransferLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Payments left out of the totals and transfers between the person's own accounts, in private preferences. The state
 * changes themselves are in [SetAsideStore] (atomic, tested); this only loads and saves.
 */
class SetAsideRepositoryImpl private constructor(
    private val prefs: android.content.SharedPreferences,
    private val store: SetAsideStore
) : SetAsideRepository by store {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("set_aside", Context.MODE_PRIVATE))

    private constructor(prefs: android.content.SharedPreferences) : this(prefs, holder(prefs))

    private companion object {
        const val EXCLUDED = "excluded"
        const val TRANSFERS = "transfers"
        const val DISMISSED = "dismissed"

        fun holder(prefs: android.content.SharedPreferences) = SetAsideStore(load(prefs)) { save(prefs, it) }

        fun save(prefs: android.content.SharedPreferences, state: SetAsides) {
            val transfers = JSONArray()
            state.transfers.forEach { transfers.put(JSONObject().put("out", it.outId ?: JSONObject.NULL).put("in", it.inId ?: JSONObject.NULL)) }
            prefs.edit()
                .putStringSet(EXCLUDED, state.excluded.mapTo(HashSet()) { it.toString() })
                .putString(TRANSFERS, transfers.toString())
                .putStringSet(DISMISSED, state.dismissed)
                .apply()
        }

        fun load(prefs: android.content.SharedPreferences): SetAsides = runCatching {
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
    }
}
