package com.zyke.message.datalayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Android exposes no public API to read RCS messages, so RCS messages are captured from the
 * messaging app's notifications (see [RcsNotificationListener]) and persisted here.
 */
object RcsMessageStore {
    private const val FILE_NAME = "rcs_messages.json"
    private const val MAX_MESSAGES = 2000
    private const val SMS_DEDUPE_WINDOW_MS = 2 * 60 * 1000L

    @Synchronized
    fun add(context: Context, message: ZMessage) {
        val all = load(context)
        if (all.any { it.sender == message.sender && it.messageContent == message.messageContent && it.date == message.date }) return
        save(context, (all + message).sortedByDescending { it.date }.take(MAX_MESSAGES))
    }

    @Synchronized
    fun load(context: Context): List<ZMessage> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                ZMessage(
                    o.getString("sender"),
                    o.getString("body"),
                    o.getLong("date"),
                    if (o.optBoolean("read", false)) ReadStatus.READ else ReadStatus.UNREAD,
                    isRcs = true
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Merges stored RCS messages into [sms], skipping ones that already appear as SMS. */
    fun mergeWith(context: Context, sms: List<ZMessage>): List<ZMessage> {
        val rcs = load(context).filter { r ->
            sms.none {
                it.messageContent == r.messageContent &&
                        kotlin.math.abs(it.date - r.date) <= SMS_DEDUPE_WINDOW_MS
            }
        }
        return sms + rcs
    }

    private fun save(context: Context, messages: List<ZMessage>) {
        val array = JSONArray()
        messages.forEach {
            array.put(
                JSONObject()
                    .put("sender", it.sender)
                    .put("body", it.messageContent)
                    .put("date", it.date)
                    .put("read", it.readStatus == ReadStatus.READ)
            )
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }
}
