package com.zyke.message.data.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Captures and restores the person's own setup by copying the preference files the app keeps it in (plan, limits,
 * cards, goals, reminders, events, entries, names, categories, muted senders, merges, set-asides, hidden accounts,
 * marks and settings). Not included: the messages (they live in the phone's own inbox), what was read from them
 * (rebuilt on the next open), sender pictures, the developer log and alarm bookkeeping.
 *
 * The snapshot is plain JSON bytes; encrypting it is [com.zyke.message.domain.backup.BackupCrypto]'s job.
 */
object PrefsBackup {
    private const val SNAPSHOT_VERSION = 1
    private val EXCLUDED = setOf("sender_images", "developer", "reminder_alarms")
    private val VALID_NAME = Regex("[a-z][a-z0-9_]{0,63}")

    /** A parsed snapshot that has been checked and is ready to apply. Holds nothing but preference values. */
    class Snapshot internal constructor(internal val files: Map<String, List<Entry>>) {
        val fileCount: Int get() = files.size
    }

    internal class Entry(val key: String, val type: String, val value: Any)

    /** Why a decrypted snapshot cannot be used. */
    class InvalidSnapshotException : Exception("The backup does not contain a usable setup.")

    private fun includes(name: String) = name !in EXCLUDED && VALID_NAME.matches(name)

    private fun existingNames(context: Context): List<String> =
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.filter(::includes)
            ?.sorted()
            .orEmpty()

    fun capture(context: Context): ByteArray {
        val app = context.applicationContext
        val prefsJson = JSONObject()
        for (name in existingNames(app)) {
            val file = JSONObject()
            for ((key, value) in app.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val entry = when (value) {
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value.toString())
                    is Long -> JSONObject().put("t", "l").put("v", value.toString())
                    is Float -> JSONObject().put("t", "f").put("v", value.toString())
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray().apply { value.forEach { if (it is String) put(it) } })
                    else -> null
                }
                if (entry != null) file.put(key, entry)
            }
            prefsJson.put(name, file)
        }
        return JSONObject().put("snapshot", SNAPSHOT_VERSION).put("prefs", prefsJson).toString().toByteArray(Charsets.UTF_8)
    }

    /** Parses and checks everything without touching the phone's data. */
    @Throws(InvalidSnapshotException::class)
    fun parse(bytes: ByteArray): Snapshot {
        try {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            if (root.optInt("snapshot", -1) != SNAPSHOT_VERSION) throw InvalidSnapshotException()
            val prefs = root.getJSONObject("prefs")
            val files = LinkedHashMap<String, List<Entry>>()
            for (name in prefs.keys()) {
                if (!includes(name)) continue
                val file = prefs.getJSONObject(name)
                val entries = ArrayList<Entry>()
                for (key in file.keys()) {
                    val e = file.getJSONObject(key)
                    val value: Any = when (e.getString("t")) {
                        "s" -> e.getString("v")
                        "b" -> e.getBoolean("v")
                        "i" -> e.getString("v").toInt()
                        "l" -> e.getString("v").toLong()
                        "f" -> e.getString("v").toFloat()
                        "ss" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                        else -> throw InvalidSnapshotException()
                    }
                    entries.add(Entry(key, e.getString("t"), value))
                }
                files[name] = entries
            }
            return Snapshot(files)
        } catch (e: JSONException) {
            throw InvalidSnapshotException()
        } catch (e: NumberFormatException) {
            throw InvalidSnapshotException()
        }
    }

    /**
     * Replaces the setup with the snapshot: every included file is cleared and then filled from it (files the backup
     * does not have end up empty). The caller must close the app straight afterwards so nothing in memory writes the
     * old values back.
     */
    @Suppress("UNCHECKED_CAST")
    fun apply(context: Context, snapshot: Snapshot) {
        val app = context.applicationContext
        for (name in (existingNames(app) + snapshot.files.keys).distinct()) {
            val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (e in snapshot.files[name].orEmpty()) {
                when (e.type) {
                    "s" -> editor.putString(e.key, e.value as String)
                    "b" -> editor.putBoolean(e.key, e.value as Boolean)
                    "i" -> editor.putInt(e.key, e.value as Int)
                    "l" -> editor.putLong(e.key, e.value as Long)
                    "f" -> editor.putFloat(e.key, e.value as Float)
                    "ss" -> editor.putStringSet(e.key, e.value as Set<String>)
                }
            }
            editor.commit()
        }
    }
}
