package com.zyke.message.data.sms

import android.content.ContentResolver
import android.net.Uri
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.mms.MmsAddress
import com.zyke.message.domain.mms.MmsMessages
import com.zyke.message.domain.mms.MmsPart
import com.zyke.message.domain.mms.MmsPartInfo
import com.zyke.message.domain.mms.MmsStructureDump

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

    /**
     * The parts of one message; text the row does not carry is read from the part's data, up to a limit and in the part's own charset.
     * Whether a rich card keeps its words in a text part, a JSON part or elsewhere is unverified; see [MmsMessages.textKind].
     */
    private fun parts(rowId: Long): List<MmsPart> {
        val found = queryParts(rowId, withCharset = true) ?: queryParts(rowId, withCharset = false).orEmpty()
        val toRead = MmsMessages.textPartsToRead(found).associateBy { it.id }
        if (toRead.isEmpty()) return found
        return found.map { part -> if (part.id in toRead) part.copy(text = readText(part.id, part.charset)) else part }
    }

    /** Null when the provider refuses the column list (a phone without the charset column). */
    private fun queryParts(rowId: Long, withCharset: Boolean): List<MmsPart>? = try {
        val columns = if (withCharset) arrayOf(ID, CONTENT_TYPE, TEXT, CHARSET) else arrayOf(ID, CONTENT_TYPE, TEXT)
        contentResolver.query(PART, columns, "$MESSAGE_ID = ?", arrayOf(rowId.toString()), "$ID ASC")?.use {
            val id = it.getColumnIndexOrThrow(ID)
            val type = it.getColumnIndexOrThrow(CONTENT_TYPE)
            val text = it.getColumnIndexOrThrow(TEXT)
            val chset = if (withCharset) it.getColumnIndexOrThrow(CHARSET) else -1
            buildList {
                while (it.moveToNext()) {
                    add(MmsPart(it.getLong(id), it.getString(type).orEmpty(), it.getString(text), seq = it.position, charset = if (chset >= 0) it.getInt(chset) else 0))
                }
            }
        }.orEmpty()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        if (withCharset) null else emptyList()
    }

    /** Up to [MAX_TEXT_BYTES] of a part's data (no readNBytes: that is API 33); null when it cannot be read. */
    private fun readBytes(partId: Long): ByteArray? = try {
        contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { stream ->
            val buffer = ByteArray(MAX_TEXT_BYTES)
            var total = 0
            while (total < buffer.size) {
                val n = stream.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            if (total == buffer.size) buffer else buffer.copyOf(total)
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        null
    }

    /** A part's data as text in the part's own charset (UTF-8 when it names none); null when it cannot be read. */
    private fun readText(partId: Long, mibCharset: Int): String? {
        val bytes = readBytes(partId) ?: return null
        val charset = try {
            java.nio.charset.Charset.forName(MmsMessages.charsetName(mibCharset))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    /**
     * Developer diagnostics: the structure of one message (types, sizes, whether there was text or data, the start of any text, address
     * types) as text, built in memory and never logged. No picture is read; the message id is not in it. Null when it cannot be read.
     */
    fun structureDump(messageId: Long): String? = runQuiet(null) {
        val rowId = MmsMessages.rowIdOf(messageId) ?: return@runQuiet null
        val infos = contentResolver.query(PART, arrayOf(ID, CONTENT_TYPE, TEXT, DATA), "$MESSAGE_ID = ?", arrayOf(rowId.toString()), "$ID ASC")?.use {
            val id = it.getColumnIndexOrThrow(ID)
            val type = it.getColumnIndexOrThrow(CONTENT_TYPE)
            val text = it.getColumnIndexOrThrow(TEXT)
            val data = it.getColumnIndexOrThrow(DATA)
            buildList {
                while (it.moveToNext()) {
                    val ct = it.getString(type).orEmpty()
                    val ownText = it.getString(text)
                    val readable = MmsMessages.textKind(MmsPart(0, ct, "x")) != MmsMessages.TextKind.NONE || ct.lowercase().startsWith("application/")
                    val bytes = if (readable && !ct.lowercase().contains("smil")) measure(it.getLong(id)) else null
                    val head = ownText ?: bytes?.second
                    add(
                        MmsPartInfo(
                            ct, bytes?.first ?: ownText?.length ?: -1, bytes?.third ?: false,
                            hadText = !ownText.isNullOrEmpty(), hadData = !it.getString(data).isNullOrEmpty(),
                            textHead = if (readable && head != null && !ct.lowercase().contains("smil")) head.take(MmsStructureDump.HEAD) else ""
                        )
                    )
                }
            }
        }.orEmpty()
        val types = HashMap<Int, Int>()
        addresses(rowId).forEach { types[it.type] = (types[it.type] ?: 0) + 1 }
        val sender = MmsMessages.sender(addresses(rowId))
        MmsStructureDump.build(sender, infos, types)
    }

    /** Size of a part's data (up to the read limit), the start of it as text, and whether the limit was hit. Null when unreadable. */
    private fun measure(partId: Long): Triple<Int, String, Boolean>? {
        val bytes = readBytes(partId) ?: return null
        return Triple(bytes.size, String(bytes, 0, minOf(bytes.size, MmsStructureDump.HEAD * 4), Charsets.UTF_8), bytes.size >= MAX_TEXT_BYTES)
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
        private const val CHARSET = "chset"
        private const val DATA = "_data"
        private const val MESSAGE_ID = "mid"
        private const val ADDRESS = "address"
        private const val ADDRESS_TYPE = "type"
        private const val MAX_TEXT_BYTES = 64 * 1024

        /** The provider's address, for watching it change. */
        val CHANGES: Uri = Uri.parse("content://mms")
    }
}
