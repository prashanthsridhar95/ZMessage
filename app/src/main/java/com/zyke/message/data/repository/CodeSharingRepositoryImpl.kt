package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.sharing.CodeSharingRepository
import com.zyke.message.domain.sharing.CodeSharingRules
import com.zyke.message.domain.sharing.SentRecord
import com.zyke.message.domain.sharing.ShareHistory
import com.zyke.message.domain.sharing.ShareLog
import com.zyke.message.domain.sharing.ShareLogEntry
import com.zyke.message.domain.sharing.ShareStatus
import com.zyke.message.domain.sharing.ShareWindow
import com.zyke.message.domain.sharing.TrustedContact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The code-sharing setup in the `code_sharing` preferences (part of a backup) and the log of what was sent in
 * `code_sharing_log` (left out of backups: it describes this phone). The log holds the service, the person's name, the
 * time and the status; the duplicate check's code keys are kept in memory only and never reach a file.
 */
class CodeSharingRepositoryImpl(context: Context) : CodeSharingRepository {
    private val prefs = context.applicationContext.getSharedPreferences("code_sharing", Context.MODE_PRIVATE)
    private val logPrefs = context.applicationContext.getSharedPreferences("code_sharing_log", Context.MODE_PRIVATE)
    private val lock = Any()
    private val memory = ArrayList<SentRecord>()

    private val _rules = MutableStateFlow(readRules())
    override val rules: StateFlow<CodeSharingRules> = _rules.asStateFlow()
    private val _log = MutableStateFlow(readLog())
    override val log: StateFlow<List<ShareLogEntry>> = _log.asStateFlow()

    override fun setEnabled(on: Boolean) = change { it.copy(enabled = on) }
    override fun setPausedUntil(at: Long) = change { it.copy(pausedUntil = at.coerceAtLeast(0L)) }
    override fun setWindow(window: ShareWindow?) = change { it.copy(window = window?.takeUnless { w -> w.isAlways }) }
    override fun setSenderName(name: String) = change { it.copy(senderName = name.trim().take(40)) }

    override fun saveContact(contact: TrustedContact) = change { rules ->
        val others = rules.contacts.filter { it.id != contact.id }
        val at = rules.contacts.indexOfFirst { it.id == contact.id }
        rules.copy(contacts = if (at >= 0) rules.contacts.map { if (it.id == contact.id) contact else it } else others + contact)
    }

    override fun removeContact(id: String) = change { it.copy(contacts = it.contacts.filter { c -> c.id != id }) }
    override fun setContactEnabled(id: String, on: Boolean) = change { rules ->
        rules.copy(contacts = rules.contacts.map { if (it.id == id) it.copy(enabled = on) else it })
    }

    override fun addLog(entry: ShareLogEntry) = changeLog { ShareLog.add(it, entry, System.currentTimeMillis()) }
    override fun updateStatus(id: String, incoming: ShareStatus) = changeLog { ShareLog.update(it, id, incoming) }
    override fun clearLog() = changeLog { emptyList() }

    override fun history(now: Long): ShareHistory = synchronized(lock) {
        memory.removeAll { now - it.at > KEEP_MEMORY_MS }
        ShareHistory(ShareLog.records(_log.value).filter { now - it.at <= KEEP_MEMORY_MS } + memory)
    }

    override fun remember(record: SentRecord) {
        synchronized(lock) {
            memory.removeAll { System.currentTimeMillis() - it.at > KEEP_MEMORY_MS }
            memory += record
        }
    }

    private fun change(edit: (CodeSharingRules) -> CodeSharingRules) {
        synchronized(lock) {
            val updated = edit(_rules.value)
            if (updated == _rules.value) return
            write(updated)
            _rules.value = updated
        }
    }

    private fun changeLog(edit: (List<ShareLogEntry>) -> List<ShareLogEntry>) {
        synchronized(lock) {
            val updated = edit(_log.value)
            if (updated == _log.value) return
            logPrefs.edit().putString(KEY_LOG, JSONArray().apply { updated.forEach { put(logJson(it)) } }.toString()).apply()
            _log.value = updated
        }
    }

    private fun write(rules: CodeSharingRules) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, rules.enabled)
            .putLong(KEY_PAUSED, rules.pausedUntil)
            .putInt(KEY_WINDOW_START, rules.window?.startMinute ?: -1)
            .putInt(KEY_WINDOW_END, rules.window?.endMinute ?: -1)
            .putString(KEY_NAME, rules.senderName)
            .putString(KEY_CONTACTS, JSONArray().apply { rules.contacts.forEach { put(contactJson(it)) } }.toString())
            .apply()
    }

    private fun readRules(): CodeSharingRules {
        val start = prefs.getInt(KEY_WINDOW_START, -1)
        val end = prefs.getInt(KEY_WINDOW_END, -1)
        return CodeSharingRules(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            contacts = runCatching { readContacts(prefs.getString(KEY_CONTACTS, null)) }.getOrDefault(emptyList()),
            pausedUntil = prefs.getLong(KEY_PAUSED, 0L),
            window = if (start in 0 until 1440 && end in 0 until 1440 && start != end) ShareWindow(start, end) else null,
            senderName = prefs.getString(KEY_NAME, "").orEmpty()
        )
    }

    private fun readContacts(json: String?): List<TrustedContact> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val services = o.getJSONArray("services").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                TrustedContact(o.getString("id"), o.getString("name"), o.getString("number"), services, o.optBoolean("enabled", true))
            }.getOrNull()
        }
    }

    private fun contactJson(c: TrustedContact) = JSONObject()
        .put("id", c.id).put("name", c.name).put("number", c.number).put("enabled", c.enabled)
        .put("services", JSONArray().apply { c.services.sorted().forEach { put(it) } })

    private fun readLog(): List<ShareLogEntry> = runCatching {
        val array = JSONArray(logPrefs.getString(KEY_LOG, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                ShareLogEntry(
                    o.getString("id"), o.getString("service"), o.getString("contact"), o.getString("name"), o.getLong("at"),
                    ShareStatus.valueOf(o.getString("status")), o.optBoolean("test", false)
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun logJson(e: ShareLogEntry) = JSONObject()
        .put("id", e.id).put("service", e.serviceId).put("contact", e.contactId).put("name", e.contactName)
        .put("at", e.at).put("status", e.status.name).put("test", e.test)

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PAUSED = "paused_until"
        const val KEY_WINDOW_START = "window_start"
        const val KEY_WINDOW_END = "window_end"
        const val KEY_NAME = "sender_name"
        const val KEY_CONTACTS = "contacts"
        const val KEY_LOG = "log"
        const val KEEP_MEMORY_MS = 60L * 60_000L
    }
}
