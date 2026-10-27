package com.zyke.message.data.sms

import android.content.Context
import com.zyke.message.domain.model.SmsMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Objects
import kotlin.math.abs

/**
 * Android exposes no public API to read RCS messages, so they are captured from the messaging
 * app's notifications (see [RcsNotificationListener]) and kept in a file in the app's private storage.
 */
class RcsMessageStore(private val context: Context) {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits whenever a new RCS message has been stored. */
    val changes: Flow<Unit> = _changes

    @Synchronized
    fun add(sender: String, body: String, date: Long) {
        val all = load()
        if (all.any { it.sender == sender && it.body == body && it.date == date }) return
        val message = SmsMessage(idFor(sender, body, date), sender, body, date, isRead = false, isRcs = true)
        save((all + message).sortedByDescending { it.date }.take(MAX_MESSAGES))
        _changes.tryEmit(Unit)
    }

    /** Adds the stored RCS messages to [sms], skipping ones that already arrived as SMS. */
    fun mergeWith(sms: List<SmsMessage>): List<SmsMessage> {
        val rcs = load().filter { r ->
            sms.none { it.body == r.body && abs(it.date - r.date) <= SMS_DEDUPE_WINDOW_MS }
        }
        return if (rcs.isEmpty()) sms else (sms + rcs).sortedByDescending { it.date }
    }

    @Synchronized
    private fun load(): List<SmsMessage> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                val sender = o.getString("sender")
                val body = o.getString("body")
                val date = o.getLong("date")
                SmsMessage(idFor(sender, body, date), sender, body, date, o.optBoolean("read", false), isRcs = true)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            emptyList()
        }
    }

    private fun save(messages: List<SmsMessage>) {
        val array = JSONArray()
        messages.forEach {
            array.put(
                JSONObject().put("sender", it.sender).put("body", it.body).put("date", it.date).put("read", it.isRead)
            )
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }

    /** Negative, so it can never collide with a real SMS row id. */
    private fun idFor(sender: String, body: String, date: Long): Long =
        -1L - abs(Objects.hash(sender, body, date).toLong())

    private companion object {
        const val FILE_NAME = "rcs_messages.json"
        const val MAX_MESSAGES = 2000
        const val SMS_DEDUPE_WINDOW_MS = 2 * 60 * 1000L
    }
}
