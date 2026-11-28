package com.zyke.message.domain.email

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

/**
 * The only IMAP commands this app can write, and the only way to write them. Each one is read-only by construction: the mailbox is
 * opened with EXAMINE (which the server treats as read-only), messages are found by sender and date, and read with BODY.PEEK, which
 * never sets the "seen" flag. There is no command here that changes, moves, flags or removes a message, and no way to hand the
 * session a raw string: [ImapSession.run] takes an [ImapCommand], nothing else. EmailPolicyTest pins that.
 */
sealed class ImapCommand {
    /** The command line without its tag and without the line ending. */
    abstract fun wire(): String

    /** `LOGIN "user" "password"`. Printable ASCII only; anything else is refused before anything is sent. */
    class Login(private val user: String, private val password: String) : ImapCommand() {
        override fun wire(): String {
            if (!printable(user) || !printable(password) || user.isEmpty() || password.isEmpty()) throw MailException(MailProblem.BAD_INPUT)
            return "LOGIN ${quote(user)} ${quote(password)}"
        }

        // The password must never reach a log or a crash report through toString.
        override fun toString(): String = "Login(<hidden>)"
    }

    /** Opens the inbox read-only. */
    data object ExamineInbox : ImapCommand() {
        override fun wire(): String = "EXAMINE INBOX"
    }

    /** `UID SEARCH SINCE d OR FROM "a" FROM "b"`: only the numbers of mails from the listed senders since the date. */
    class UidSearch(private val since: LocalDate, private val senders: List<String>) : ImapCommand() {
        override fun wire(): String {
            if (senders.isEmpty() || senders.size > MAX_SENDERS || senders.any { !SENDER.matches(it) }) throw MailException(MailProblem.BAD_INPUT)
            return "UID SEARCH SINCE ${imapDate(since)} ${anyOf(senders)}"
        }

        private fun anyOf(list: List<String>): String =
            if (list.size == 1) "FROM \"${list[0]}\"" else "OR FROM \"${list[0]}\" ${anyOf(list.drop(1))}"
    }

    /** The From, Subject, Date, Message-ID and Authentication-Results headers of some mails, without marking them read. */
    class UidFetchHeaders(private val uids: List<Long>) : ImapCommand() {
        override fun wire(): String {
            if (uids.isEmpty() || uids.size > MAX_UIDS || uids.any { it <= 0 }) throw MailException(MailProblem.BAD_INPUT)
            return "UID FETCH ${uids.joinToString(",")} (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID AUTHENTICATION-RESULTS)])"
        }
    }

    /** The first [maxBytes] bytes of one mail, without marking it read. */
    class UidFetchBody(private val uid: Long, private val maxBytes: Int) : ImapCommand() {
        override fun wire(): String {
            if (uid <= 0 || maxBytes <= 0 || maxBytes > MAX_FETCH_BYTES) throw MailException(MailProblem.BAD_INPUT)
            return "UID FETCH $uid (BODY.PEEK[]<0.$maxBytes>)"
        }
    }

    /** Ends the session. */
    data object Logout : ImapCommand() {
        override fun wire(): String = "LOGOUT"
    }

    companion object {
        const val MAX_SENDERS = 40
        const val MAX_UIDS = 100
        const val MAX_FETCH_BYTES = 512 * 1024
        private val SENDER = Regex("[a-z0-9._+-]{1,64}@[a-z0-9.-]{3,100}")
        private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        private fun printable(s: String) = s.all { it.code in PRINTABLE }
        private val PRINTABLE = 0x20..0x7E

        /** A quoted string: backslash and quote are escaped. */
        fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** `28-Nov-2026`, with English month names whatever the phone's language. */
        fun imapDate(d: LocalDate): String = String.format(java.util.Locale.ROOT, "%02d-%s-%04d", d.dayOfMonth, MONTHS[d.monthValue - 1], d.year)
    }
}

/** One piece of an untagged reply: the text on the line, or a literal (the bytes the server sent after `{n}`). */
sealed interface Piece {
    class Text(val text: String) : Piece
    class Literal(val bytes: ByteArray) : Piece
}

/** An untagged line (`* ...`) of a reply, with its literals kept apart from the text. */
class Untagged(val pieces: List<Piece>) {
    /** All the text of the line, literals left out. */
    val text: String get() = pieces.filterIsInstance<Piece.Text>().joinToString(" ") { it.text }
    val literals: List<ByteArray> get() = pieces.filterIsInstance<Piece.Literal>().map { it.bytes }
}

enum class ImapStatus { OK, NO, BAD }

/** The server's answer to one command: its status and text, and every untagged line that came before it. */
class ImapReply(val status: ImapStatus, val text: String, val untagged: List<Untagged>) {
    override fun toString(): String = "ImapReply($status)"
}

/**
 * Speaks IMAP over a pair of streams the caller already opened (a TLS socket in the app; a script in the tests). It keeps
 * no state beyond the tag counter, never logs, and bounds everything it reads: a line, a literal, and a whole reply.
 * Timeouts are the socket's. A server that says something it cannot follow ends the session with [MailProblem.PROTOCOL].
 */
class ImapSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxLiteral: Int = ImapCommand.MAX_FETCH_BYTES + SLACK,
    private val maxReply: Int = MAX_REPLY_BYTES
) {
    private var counter = 0

    /** Reads the server's greeting. Only `* OK` is accepted: PREAUTH and BYE end the session. */
    fun greeting() {
        val line = readLine()
        if (!line.startsWith("* OK", ignoreCase = true)) throw MailException(MailProblem.PROTOCOL)
    }

    /** Sends [command] and reads until its tagged answer. */
    fun run(command: ImapCommand): ImapReply {
        counter++
        val tag = String.format(java.util.Locale.ROOT, "Z%03d", counter)
        val line = tag + " " + command.wire() + "\r\n"
        output.write(line.toByteArray(Charsets.US_ASCII))
        output.flush()
        val untagged = ArrayList<Untagged>()
        var total = 0
        while (true) {
            val first = readLine()
            if (first.startsWith("$tag ")) {
                val rest = first.substring(tag.length + 1)
                val word = rest.substringBefore(' ').uppercase()
                val status = when (word) {
                    "OK" -> ImapStatus.OK
                    "NO" -> ImapStatus.NO
                    "BAD" -> ImapStatus.BAD
                    else -> throw MailException(MailProblem.PROTOCOL)
                }
                return ImapReply(status, rest.substringAfter(' ', ""), untagged)
            }
            if (!first.startsWith("*")) {
                // A continuation request or noise: this client never sends a literal, so it cannot be answered.
                if (first.startsWith("+")) throw MailException(MailProblem.PROTOCOL)
                continue
            }
            val line = readUntagged(first)
            total += line.pieces.sumOf { if (it is Piece.Literal) it.bytes.size else (it as Piece.Text).text.length }
            if (total > maxReply) throw MailException(MailProblem.PROTOCOL)
            untagged += line
        }
    }

    private fun readUntagged(firstLine: String): Untagged {
        val pieces = ArrayList<Piece>()
        var line = firstLine
        while (true) {
            val m = LITERAL_END.find(line)
            if (m == null) {
                pieces += Piece.Text(line)
                return Untagged(pieces)
            }
            pieces += Piece.Text(line.substring(0, m.range.first))
            val size = m.groupValues[1].toIntOrNull() ?: throw MailException(MailProblem.PROTOCOL)
            if (size < 0 || size > maxLiteral) throw MailException(MailProblem.PROTOCOL)
            pieces += Piece.Literal(readExactly(size))
            line = readLine()
        }
    }

    private fun readExactly(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) throw MailException(MailProblem.NETWORK)
            read += n
        }
        return buffer
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream(LINE_START)
        while (true) {
            val b = input.read()
            if (b < 0) throw MailException(MailProblem.NETWORK)
            if (b == '\n'.code) break
            if (out.size() >= MAX_LINE) throw MailException(MailProblem.PROTOCOL)
            out.write(b)
        }
        val bytes = out.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.ISO_8859_1)
    }

    private companion object {
        const val MAX_LINE = 16 * 1024
        const val MAX_REPLY_BYTES = 8 * 1024 * 1024
        const val SLACK = 64 * 1024
        const val LINE_START = 128
        val LITERAL_END = Regex("""\{(\d{1,9})\}$""")
    }
}
