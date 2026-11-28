package com.zyke.message.domain.email

import java.time.LocalDate

/**
 * One pass over a mailbox, written against [ImapSession] so it can be tested with a scripted server. The order is fixed, and the
 * order is the privacy promise:
 *
 *  1. Sign in, open the inbox read-only.
 *  2. Ask the server for the numbers of the mails FROM the listed addresses since a date. Nothing else is ever asked for by number.
 *  3. Read only the headers (From, Subject, Date, Message-ID, Authentication-Results) of those, and check the From address against the
 *     list again here: a server's FROM search matches pieces of text, so `alerts@hdfcbank.net.example.com` or `xalerts@hdfcbank.net`
 *     could come back and are dropped. A mail the provider itself marked as failing its sender checks (dkim, spf or dmarc = fail) is dropped too.
 *  4. Read the first 200 KB of each mail that is left, and nothing of any other mail.
 *  5. Sign out.
 *
 * Neither the address, the password, a header nor a body is ever put in an exception or a message.
 */
object ImapMailbox {

    /** Signs in and out; used by Connect. */
    fun check(session: ImapSession, address: String, password: String) {
        session.greeting()
        login(session, address, password)
        logout(session)
    }

    fun fetch(session: ImapSession, request: MailRequest, allowed: (String) -> Boolean): MailBatch {
        session.greeting()
        login(session, request.address, request.password)
        val validity = examine(session)
        val found = search(session, request.since, request.senders)
        val fresh = if (validity == request.knownUidValidity) found.filter { it > request.afterUid } else found
        // Newest first; a pass reads at most twice the limit of headers.
        val candidates = fresh.sortedDescending().take(request.maxMessages * 2)
        val headers = ArrayList<MailHeader>()
        for (chunk in candidates.chunked(ImapCommand.MAX_UIDS)) headers += headers(session, chunk)
        val passing = headers.filter { allowed(it.from) && !it.failedAuthentication }.sortedByDescending { it.uid }.take(request.maxMessages)
        val mails = ArrayList<RawMail>(passing.size)
        for (h in passing) {
            val raw = body(session, h.uid, request.maxBytesPerMail) ?: continue
            val parsed = Mime.parse(raw)
            val date = h.dateMillis ?: Mime.parseDate(parsed.headers["date"].orEmpty()) ?: 0L
            mails += RawMail(h.uid, h.from, h.subject, date, h.messageId, parsed.text)
        }
        logout(session)
        val highest = (found.maxOrNull() ?: 0L).coerceAtLeast(if (validity == request.knownUidValidity) request.afterUid else 0L)
        return MailBatch(validity, highest, mails, searched = fresh.size, skipped = fresh.size - mails.size)
    }

    private fun login(session: ImapSession, address: String, password: String) {
        val reply = session.run(ImapCommand.Login(address, password))
        if (reply.status != ImapStatus.OK) throw MailException(problemOfLoginReply(reply.text))
    }

    /** What the provider's refusal text says: the wrong password, or a block on IMAP or on this kind of sign-in. */
    fun problemOfLoginReply(text: String): MailProblem {
        val t = text.lowercase()
        val blocked = listOf(
            "imap access is disabled", "imap is disabled", "imap is not enabled", "imap not enabled", "web login required", "weblogin",
            "application-specific password required", "app password required", "less secure", "basic authentication", "authenticate failed. please", "disabled"
        )
        return if (blocked.any { it in t }) MailProblem.PROVIDER_BLOCKED else MailProblem.WRONG_PASSWORD
    }

    private fun examine(session: ImapSession): Long {
        val reply = session.run(ImapCommand.ExamineInbox)
        if (reply.status != ImapStatus.OK) throw MailException(MailProblem.PROTOCOL)
        val line = (reply.untagged.map { it.text } + reply.text).firstOrNull { UID_VALIDITY.containsMatchIn(it) }
        return line?.let { UID_VALIDITY.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
    }

    private fun search(session: ImapSession, since: LocalDate, senders: List<String>): List<Long> {
        val reply = session.run(ImapCommand.UidSearch(since, senders))
        if (reply.status != ImapStatus.OK) throw MailException(MailProblem.PROTOCOL)
        return reply.untagged.map { it.text.trim() }.filter { it.startsWith("* SEARCH", ignoreCase = true) }
            .flatMap { it.substring("* SEARCH".length).trim().split(' ') }
            .mapNotNull { it.toLongOrNull() }.filter { it > 0 }.distinct()
    }

    private class MailHeader(val uid: Long, val from: String, val subject: String, val dateMillis: Long?, val messageId: String, val failedAuthentication: Boolean)

    private fun headers(session: ImapSession, uids: List<Long>): List<MailHeader> {
        val reply = session.run(ImapCommand.UidFetchHeaders(uids))
        if (reply.status != ImapStatus.OK) throw MailException(MailProblem.PROTOCOL)
        val out = ArrayList<MailHeader>()
        for (u in reply.untagged) {
            val uid = UID.find(u.text)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val block = u.literals.firstOrNull()?.let { String(it, Charsets.ISO_8859_1) } ?: continue
            val h = Mime.parseHeaders(block)
            val from = Mime.addressOf(h["from"].orEmpty())
            if (from.isEmpty()) continue
            out += MailHeader(
                uid, from, Mime.decodeHeader(h["subject"].orEmpty()).take(MAX_SUBJECT), Mime.parseDate(h["date"].orEmpty()),
                h["message-id"].orEmpty().trim('<', '>', ' ').take(MAX_ID), AuthResults.failed(h["authentication-results"].orEmpty())
            )
        }
        return out
    }

    private fun body(session: ImapSession, uid: Long, maxBytes: Int): ByteArray? {
        val reply = session.run(ImapCommand.UidFetchBody(uid, maxBytes))
        if (reply.status != ImapStatus.OK) return null
        return reply.untagged.firstOrNull { UID.find(it.text)?.groupValues?.get(1)?.toLongOrNull() == uid }?.literals?.firstOrNull()
    }

    private fun logout(session: ImapSession) {
        // The answer does not matter; the connection is closed straight after.
        try { session.run(ImapCommand.Logout) } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) { /* closing anyway */ }
    }

    private val UID_VALIDITY = Regex("""\[UIDVALIDITY (\d+)]""", RegexOption.IGNORE_CASE)
    private val UID = Regex("""\bUID (\d+)""", RegexOption.IGNORE_CASE)
    private const val MAX_SUBJECT = 200
    private const val MAX_ID = 200
}

/** The provider's own verdict on a mail's sender checks, from the header it adds. Only an explicit failure rejects a mail. */
object AuthResults {
    private val FAIL = Regex("""(?i)\b(?:dkim|spf|dmarc)\s*=\s*(?:fail|hardfail|permerror)\b""")

    fun failed(header: String): Boolean = FAIL.containsMatchIn(header)
}
