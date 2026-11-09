package com.zyke.message.data.sms

import android.content.ContentResolver
import android.net.Uri
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.mms.MmsAddress
import com.zyke.message.domain.mms.MmsMessages
import com.zyke.message.domain.mms.MmsPart

/**
 * Reads picture (MMS) messages that sit in the phone's MMS provider, with the same READ_SMS permission as the SMS inbox. Only the
 * text parts are read here; pictures are read one message at a time, when it is opened ([MmsImages]). Chat (RCS) conversations in
 * Google Messages are not in this provider at all.
 *
 * Every door is wrapped: a phone without the provider, a denied permission or an odd row yields nothing, never a crash.
 */
class MmsDataSource(private val contentResolver: ContentResolver) {

    /** A row of the MMS inbox, without its text. */
    class Stub(val messageId: Long, val date: Long, val isRead: Boolean)

    /** The id, date and read flag of every MMS in the inbox dated [sinceMillis] or later (all when 0): no part is touched. */
    fun stubs(sinceMillis: Long): List<Stub> = runQuiet(emptyList()) {
        val since = if (sinceMillis > 0) arrayOf(secondsCeil(sinceMillis).toString()) else null
        contentResolver.query(INBOX, arrayOf(ID, DATE, READ), if (since != null) "$DATE >= ?" else null, since, "$DATE DESC")?.use {
            val id = it.getColumnIndexOrThrow(ID)
            val date = it.getColumnIndexOrThrow(DATE)
            val read = it.getColumnIndexOrThrow(READ)
            buildList { while (it.moveToNext()) add(Stub(MmsMessages.idFor(it.getLong(id)), MmsMessages.dateMillis(it.getLong(date)), it.getInt(read) == 1)) }
        }.orEmpty()
    }

    /** The MMS dated from [fromMillis] up to [untilMillis], with their text and sender. */
    fun readWindow(fromMillis: Long, untilMillis: Long): List<SmsMessage> = runQuiet(emptyList()) {
        val rows = contentResolver.query(
            INBOX, arrayOf(ID, DATE, READ), "$DATE >= ? AND $DATE < ?",
            arrayOf(secondsCeil(fromMillis).toString(), secondsCeil(untilMillis).toString()), "$DATE DESC"
        )?.use {
            val id = it.getColumnIndexOrThrow(ID)
            val date = it.getColumnIndexOrThrow(DATE)
            val read = it.getColumnIndexOrThrow(READ)
            buildList { while (it.moveToNext()) add(Triple(it.getLong(id), it.getLong(date), it.getInt(read) == 1)) }
        }.orEmpty()
        rows.mapNotNull { (rowId, rawDate, isRead) ->
            val parts = parts(rowId)
            val text = MmsMessages.text(parts)
            val sender = MmsMessages.sender(addresses(rowId))
            val hasImage = MmsMessages.hasImage(parts)
            if (!MmsMessages.isUsable(sender, text, hasImage)) return@mapNotNull null
            SmsMessage(
                id = MmsMessages.idFor(rowId),
                sender = sender,
                body = text.ifEmpty { MmsMessages.PICTURE_ONLY_TEXT },
                date = MmsMessages.dateMillis(rawDate),
                isRead = isRead,
                isMms = true
            )
        }
    }

    /** The parts of one message; text that the row does not carry is read from the part, up to a limit. */
    private fun parts(rowId: Long): List<MmsPart> {
        val found = contentResolver.query(PART, arrayOf(ID, CONTENT_TYPE, TEXT), "$MESSAGE_ID = ?", arrayOf(rowId.toString()), "$ID ASC")?.use {
            val id = it.getColumnIndexOrThrow(ID)
            val type = it.getColumnIndexOrThrow(CONTENT_TYPE)
            val text = it.getColumnIndexOrThrow(TEXT)
            buildList { while (it.moveToNext()) add(MmsPart(it.getLong(id), it.getString(type).orEmpty(), it.getString(text), seq = it.position)) }
        }.orEmpty()
        val toRead = MmsMessages.textPartsToRead(found).map { it.id }.toSet()
        if (toRead.isEmpty()) return found
        return found.map { part -> if (part.id in toRead) part.copy(text = readText(part.id)) else part }
    }

    private fun readText(partId: Long): String? = try {
        contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { stream ->
            val buffer = ByteArray(MAX_TEXT_BYTES)
            var total = 0
            while (total < buffer.size) {
                val n = stream.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            String(buffer, 0, total, Charsets.UTF_8)
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        null
    }

    private fun addresses(rowId: Long): List<MmsAddress> =
        contentResolver.query(Uri.parse("content://mms/$rowId/addr"), arrayOf(ADDRESS, ADDRESS_TYPE), null, null, null)?.use {
            val address = it.getColumnIndexOrThrow(ADDRESS)
            val type = it.getColumnIndexOrThrow(ADDRESS_TYPE)
            buildList { while (it.moveToNext()) add(MmsAddress(it.getString(address).orEmpty(), it.getInt(type))) }
        }.orEmpty()

    private inline fun <T> runQuiet(fallback: T, block: () -> T): T = try {
        block()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        com.zyke.message.data.diag.DevLog.d("sync") { "mms: not readable (${e.javaClass.simpleName})" }
        fallback
    }

    private fun secondsCeil(millis: Long): Long = (millis + 999L) / 1000L

    companion object {
        /** The folder of received messages; the same rows as msg_box = 1. */
        private val INBOX: Uri = Uri.parse("content://mms/inbox")
        private val PART: Uri = Uri.parse("content://mms/part")
        private const val ID = "_id"
        private const val DATE = "date"
        private const val READ = "read"
        private const val CONTENT_TYPE = "ct"
        private const val TEXT = "text"
        private const val MESSAGE_ID = "mid"
        private const val ADDRESS = "address"
        private const val ADDRESS_TYPE = "type"
        private const val MAX_TEXT_BYTES = 64 * 1024

        /** The provider's address, for watching it change. */
        val CHANGES: Uri = Uri.parse("content://mms")
    }
}
