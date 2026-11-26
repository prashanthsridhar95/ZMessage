package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.balance.BalanceCheckRepository
import com.zyke.message.domain.balance.BalanceRequest
import com.zyke.message.domain.balance.BalanceRequests
import com.zyke.message.domain.balance.EnquiryEdit
import com.zyke.message.domain.balance.EnquirySetup
import com.zyke.message.domain.balance.RefreshEntry
import com.zyke.message.domain.balance.RefreshRun
import com.zyke.message.domain.balance.RefreshSkip
import com.zyke.message.domain.balance.RequestStatus
import com.zyke.message.domain.balance.Uan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Check balance by SMS" in two preference files. `balance_check` holds what the person changed in the bank presets (a number and
 * some words per bank, no account digits) and is part of a backup. `balance_check_device` holds the UAN and the last request per
 * account (key, time, status) and the latest refresh before the digest (per account: key, label, time, skip reason); it describes this phone and is left out of backups. No message text, bank reply or account number
 * is stored in either.
 */
class BalanceCheckRepositoryImpl(context: Context) : BalanceCheckRepository {
    private val prefs = context.applicationContext.getSharedPreferences("balance_check", Context.MODE_PRIVATE)
    private val device = context.applicationContext.getSharedPreferences("balance_check_device", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _setup = MutableStateFlow(readSetup())
    override val setup: StateFlow<EnquirySetup> = _setup.asStateFlow()
    private val _uan = MutableStateFlow(Uan.clean(device.getString(KEY_UAN, "").orEmpty()))
    override val uan: StateFlow<String> = _uan.asStateFlow()
    private val _requests = MutableStateFlow(readRequests())
    override val requests: StateFlow<Map<String, BalanceRequest>> = _requests.asStateFlow()

    private val _skip = MutableStateFlow(readSkip())
    override val skipConfirm: StateFlow<Set<String>> = _skip.asStateFlow()

    private val _refresh = MutableStateFlow(readRefresh())
    override val refreshRun: StateFlow<RefreshRun?> = _refresh.asStateFlow()

    override fun saveRefreshRun(run: RefreshRun) {
        synchronized(lock) {
            val entries = JSONArray()
            run.entries.forEach { e ->
                entries.put(JSONObject().put("key", e.key).put("label", e.label).put("at", e.askedAt ?: 0L).put("skip", e.skip?.name.orEmpty()).put("failed", e.failed))
            }
            device.edit().putString(KEY_REFRESH, JSONObject().put("digestAt", run.digestAt).put("ranAt", run.ranAt).put("entries", entries).toString()).apply()
            _refresh.value = run
        }
    }

    override fun setSkipConfirm(key: String, skip: Boolean) {
        synchronized(lock) {
            val updated = if (skip) _skip.value + key else _skip.value - key
            if (updated == _skip.value) return
            device.edit().putStringSet(KEY_SKIP, updated).apply()
            _skip.value = updated
        }
    }

    override fun saveEdit(key: String, edit: EnquiryEdit) = changeSetup { it + (key to edit) }
    override fun clearEdit(key: String) = changeSetup { it - key }

    override fun saveUan(uan: String) {
        synchronized(lock) {
            val clean = Uan.clean(uan)
            device.edit().putString(KEY_UAN, clean).apply()
            _uan.value = clean
        }
    }

    override fun record(request: BalanceRequest) = changeRequests { it + (request.key to request) }

    override fun updateStatus(key: String, sentAt: Long, incoming: RequestStatus) = changeRequests { all ->
        val current = all[key]
        if (current == null || current.sentAt != sentAt) all else all + (key to current.copy(status = BalanceRequests.advance(current.status, incoming)))
    }

    override fun clearRequest(key: String) = changeRequests { it - key }

    private fun changeSetup(edit: (Map<String, EnquiryEdit>) -> Map<String, EnquiryEdit>) {
        synchronized(lock) {
            val updated = edit(_setup.value.edits)
            if (updated == _setup.value.edits) return
            val array = JSONArray()
            updated.forEach { (key, e) -> array.put(JSONObject().put("key", key).put("number", e.number).put("template", e.template).put("name", e.name)) }
            prefs.edit().putString(KEY_EDITS, array.toString()).apply()
            _setup.value = EnquirySetup(updated)
        }
    }

    private fun changeRequests(edit: (Map<String, BalanceRequest>) -> Map<String, BalanceRequest>) {
        synchronized(lock) {
            val updated = edit(_requests.value)
            if (updated == _requests.value) return
            val array = JSONArray()
            updated.values.forEach { r -> array.put(JSONObject().put("key", r.key).put("at", r.sentAt).put("status", r.status.name)) }
            device.edit().putString(KEY_REQUESTS, array.toString()).apply()
            _requests.value = updated
        }
    }

    private fun readSetup(): EnquirySetup = runCatching {
        val array = JSONArray(prefs.getString(KEY_EDITS, null) ?: return EnquirySetup())
        EnquirySetup((0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                o.getString("key") to EnquiryEdit(o.getString("number"), o.getString("template"), o.optString("name", ""))
            }.getOrNull()
        }.toMap())
    }.getOrDefault(EnquirySetup())

    private fun readRequests(): Map<String, BalanceRequest> = runCatching {
        val array = JSONArray(device.getString(KEY_REQUESTS, null) ?: return emptyMap())
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val key = o.getString("key")
                key to BalanceRequest(key, o.getLong("at"), RequestStatus.valueOf(o.getString("status")))
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun readRefresh(): RefreshRun? = runCatching {
        val o = JSONObject(device.getString(KEY_REFRESH, null) ?: return null)
        val array = o.getJSONArray("entries")
        RefreshRun(o.getLong("digestAt"), o.getLong("ranAt"), (0 until array.length()).mapNotNull { i ->
            runCatching {
                val e = array.getJSONObject(i)
                val at = e.getLong("at")
                RefreshEntry(e.getString("key"), e.getString("label"), at.takeIf { it > 0L }, e.optString("skip", "").takeIf { it.isNotEmpty() }?.let { RefreshSkip.valueOf(it) }, e.optBoolean("failed", false))
            }.getOrNull()
        })
    }.getOrNull()

    // On this phone only (the device file, not backed up): a restored backup on another phone asks again once.
    private fun readSkip(): Set<String> = runCatching { device.getStringSet(KEY_SKIP, null)?.toSet() }.getOrNull() ?: emptySet()

    private companion object {
        const val KEY_SKIP = "skip_confirm"
        const val KEY_EDITS = "edits"
        const val KEY_UAN = "uan"
        const val KEY_REQUESTS = "requests"
        const val KEY_REFRESH = "refresh_run"
    }
}
