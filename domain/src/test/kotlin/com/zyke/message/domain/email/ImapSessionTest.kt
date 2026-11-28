package com.zyke.message.domain.email

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The IMAP client, against a scripted server: the exact commands it writes, and what it does with every answer. */
class ImapSessionTest {
    private val crlf = "\r\n"

    /** A server whose whole side of the conversation is written down beforehand; the tags are Z001, Z002 ... in order. */
    private class Script(vararg parts: ByteArray) {
        val sent = ByteArrayOutputStream()
        val bytes = parts.fold(ByteArray(0)) { a, b -> a + b }
        fun session() = ImapSession(ByteArrayInputStream(bytes), sent)
        fun commands(): List<String> = String(sent.toByteArray(), Charsets.US_ASCII).split("\r\n").filter { it.isNotEmpty() }
    }

    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun literal(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun headerFetch(uid: Long, from: String, subject: String = "UPI alert", extra: String = ""): ByteArray {
        val block = "From: $from\r\nSubject: $subject\r\nDate: Sat, 28 Nov 2026 14:35:10 +0530\r\nMessage-ID: <m$uid@x>\r\n$extra\r\n"
        return b("* $uid FETCH (UID $uid BODY[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID AUTHENTICATION-RESULTS)] {${block.length}}$crlf") + literal(block) + b(")$crlf")
    }

    private fun bodyFetch(uid: Long, mail: String): ByteArray =
        b("* $uid FETCH (UID $uid BODY[]<0> {${mail.length}}$crlf") + literal(mail) + b(")$crlf")

    private val upiMail = "From: alerts@hdfcbank.net\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nRs.250.00 has been debited from account **1234 to VPA chai@okhdfcbank (Chai Point) on 28-11-26. Your UPI transaction reference number is 612345678901.\r\n"

    private fun request(known: Long = 0, after: Long = 0) = MailRequest(
        EmailProvider.GMAIL, "me@gmail.com", "abcdabcdabcdabcd", LocalDate.of(2026, 11, 1),
        listOf("alerts@hdfcbank.net", "alerts@hdfcbank.bank.in"), known, after
    )

    @Test
    fun aFullPassWritesOnlyReadOnlyCommands_andReadsOnlyListedSenders() {
        val script = Script(
            b("* OK IMAP4rev1 ready$crlf"),
            b("Z001 OK LOGIN completed$crlf"),
            b("* 5 EXISTS$crlf* OK [UIDVALIDITY 777] UIDs valid$crlf* OK [UIDNEXT 20]$crlf Z002 ignored$crlf".replace(" Z002 ignored$crlf", "") + "Z002 OK [READ-ONLY] EXAMINE completed$crlf"),
            b("* SEARCH 3 7 9$crlf" + "Z003 OK SEARCH completed$crlf"),
            headerFetch(3, "\"alerts@hdfcbank.net\" <evil@example.com>"),
            headerFetch(7, "HDFC Bank <alerts@hdfcbank.net>"),
            headerFetch(9, "HDFC Bank <alerts@hdfcbank.net.example.com>"),
            b("Z004 OK FETCH completed$crlf"),
            bodyFetch(7, upiMail),
            b("Z005 OK FETCH completed$crlf"),
            b("* BYE logging out$crlf" + "Z006 OK LOGOUT completed$crlf")
        )
        val batch = ImapMailbox.fetch(script.session(), request(), EmailAlertSenders::isAllowed)
        assertEquals(777L, batch.uidValidity)
        assertEquals(9L, batch.highestUid)
        assertEquals(3, batch.searched)
        assertEquals(listOf(7L), batch.mails.map { it.uid })
        val mail = batch.mails.single()
        assertEquals("alerts@hdfcbank.net", mail.from)
        assertTrue("612345678901" in mail.text)
        assertEquals("m7@x", mail.messageId)

        val sent = script.commands()
        assertEquals(
            listOf(
                "Z001 LOGIN \"me@gmail.com\" \"abcdabcdabcdabcd\"",
                "Z002 EXAMINE INBOX",
                "Z003 UID SEARCH SINCE 01-Nov-2026 OR FROM \"alerts@hdfcbank.net\" FROM \"alerts@hdfcbank.bank.in\"",
                "Z004 UID FETCH 9,7,3 (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID AUTHENTICATION-RESULTS)])",
                "Z005 UID FETCH 7 (BODY.PEEK[]<0.204800>)",
                "Z006 LOGOUT"
            ),
            sent
        )
    }

    @Test
    fun noCommandCanChangeAMailbox() {
        val everything = listOf(
            ImapCommand.Login("a@b.cc", "password1").wire(), ImapCommand.ExamineInbox.wire(),
            ImapCommand.UidSearch(LocalDate.of(2026, 1, 2), listOf("a@b.cc")).wire(), ImapCommand.UidFetchHeaders(listOf(1, 2)).wire(),
            ImapCommand.UidFetchBody(5, 1000).wire(), ImapCommand.Logout.wire()
        )
        val banned = Regex("\\b(STORE|EXPUNGE|DELETE|COPY|APPEND|SELECT|CREATE|RENAME|MOVE|IDLE|SUBSCRIBE|CLOSE)\\b|\\\\(Seen|Deleted|Flagged)")
        everything.forEach { assertFalse(it, banned.containsMatchIn(it)) }
        // Every read of a message body is a peek, so the mail is never marked as read.
        assertTrue(everything.filter { "BODY[" in it || "BODY.PEEK[" in it }.all { "BODY.PEEK[" in it && "BODY[" !in it })
    }

    @Test
    fun aMailTheProviderSaysFailedItsSenderChecksIsNotRead() {
        val script = Script(
            b("* OK ready$crlf"), b("Z001 OK done$crlf"), b("* OK [UIDVALIDITY 1]$crlf" + "Z002 OK done$crlf"), b("* SEARCH 4$crlf" + "Z003 OK done$crlf"),
            headerFetch(4, "alerts@hdfcbank.net", extra = "Authentication-Results: mx.google.com; dkim=fail header.i=@hdfcbank.net; spf=pass\r\n"),
            b("Z004 OK done$crlf"), b("Z005 OK bye$crlf")
        )
        val batch = ImapMailbox.fetch(script.session(), request(), EmailAlertSenders::isAllowed)
        assertTrue(batch.mails.isEmpty())
        assertEquals(1, batch.skipped)
        assertFalse(script.commands().any { "UID FETCH 4 (BODY.PEEK[]" in it })
    }

    @Test
    fun mailsAlreadySeenAreNotReadAgain_unlessTheMailboxWasRenumbered() {
        fun run(known: Long, after: Long): List<String> {
            val script = Script(
                b("* OK ready$crlf"), b("Z001 OK done$crlf"), b("* OK [UIDVALIDITY 777]$crlf" + "Z002 OK done$crlf"), b("* SEARCH 3 7 9$crlf" + "Z003 OK done$crlf"),
                headerFetch(9, "alerts@hdfcbank.net"), b("Z004 OK done$crlf"), bodyFetch(9, upiMail), b("Z005 OK done$crlf"), b("Z006 OK bye$crlf")
            )
            ImapMailbox.fetch(script.session(), request(known, after), EmailAlertSenders::isAllowed)
            return script.commands().filter { "UID FETCH" in it && "HEADER" in it }.map { it.substringAfter("UID FETCH ").substringBefore(" (") }
        }
        assertEquals(listOf("9"), run(known = 777, after = 7))
        assertEquals(listOf("9,7,3"), run(known = 111, after = 7))
    }

    @Test
    fun wrongPasswordAndBlockedImapAreTold_withoutTheTextBeingKept() {
        fun failWith(text: String): MailProblem {
            val script = Script(b("* OK ready$crlf"), b("Z001 NO $text$crlf"))
            try { ImapMailbox.check(script.session(), "me@gmail.com", "abcdabcdabcdabcd"); fail() } catch (e: MailException) {
                assertFalse("abcdabcd" in e.toString() + e.message.orEmpty())
                return e.problem
            }
            error("unreachable")
        }
        assertEquals(MailProblem.WRONG_PASSWORD, failWith("[AUTHENTICATIONFAILED] Invalid credentials (Failure)"))
        assertEquals(MailProblem.PROVIDER_BLOCKED, failWith("[ALERT] Application-specific password required: https://support.google.com/accounts/answer/185833"))
        assertEquals(MailProblem.PROVIDER_BLOCKED, failWith("LOGIN failed. IMAP access is disabled for your account"))
    }

    @Test
    fun badInputIsRefusedBeforeAnythingIsSent() {
        for (password in listOf("pass\r\nA1 LOGOUT", "passéword", "")) {
            val script = Script(b("* OK ready$crlf"))
            try { ImapMailbox.check(script.session(), "me@gmail.com", password); fail("sent: " + script.commands()) } catch (e: MailException) {
                assertEquals(MailProblem.BAD_INPUT, e.problem)
            }
            assertTrue(script.commands().isEmpty())
        }
        // A quote or a backslash in the password is escaped, not able to end the string.
        assertEquals("LOGIN \"u@b.cc\" \"a\\\"b\\\\c12345\"", ImapCommand.Login("u@b.cc", "a\"b\\c12345").wire())
        // A search sender with a quote or space is refused.
        try { ImapCommand.UidSearch(LocalDate.of(2026, 1, 1), listOf("a\" ALL \"b@c.dd")).wire(); fail() } catch (e: MailException) { assertEquals(MailProblem.BAD_INPUT, e.problem) }
        assertEquals("Login(<hidden>)", ImapCommand.Login("u@b.cc", "secretsecret").toString())
    }

    @Test
    fun serverTrouble_isBounded() {
        // PREAUTH or BYE as a greeting, a continuation request, an endless line, a huge literal, a dropped connection.
        for (greeting in listOf("* PREAUTH hello", "* BYE go away", "garbage")) {
            try { Script(b(greeting + crlf)).session().greeting(); fail(greeting) } catch (e: MailException) { assertEquals(MailProblem.PROTOCOL, e.problem) }
        }
        val s1 = Script(b("* OK ready$crlf"), b("+ send more$crlf")).session().also { it.greeting() }
        try { s1.run(ImapCommand.ExamineInbox); fail() } catch (e: MailException) { assertEquals(MailProblem.PROTOCOL, e.problem) }
        val s2 = Script(b("* OK ready$crlf"), b("x".repeat(20_000) + crlf)).session().also { it.greeting() }
        try { s2.run(ImapCommand.ExamineInbox); fail() } catch (e: MailException) { assertEquals(MailProblem.PROTOCOL, e.problem) }
        val s3 = Script(b("* OK ready$crlf"), b("* 1 FETCH (UID 1 BODY[] {99999999}$crlf")).session().also { it.greeting() }
        try { s3.run(ImapCommand.ExamineInbox); fail() } catch (e: MailException) { assertEquals(MailProblem.PROTOCOL, e.problem) }
        val s4 = Script(b("* OK ready$crlf"), b("* SEARCH 1")).session().also { it.greeting() }
        try { s4.run(ImapCommand.ExamineInbox); fail() } catch (e: MailException) { assertEquals(MailProblem.NETWORK, e.problem) }
    }

    @Test
    fun theDateIsWrittenInEnglishWhateverThePhoneSpeaks() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("ar", "EG"))
            assertEquals("UID SEARCH SINCE 05-Mar-2026 FROM \"a@b.cc\"", ImapCommand.UidSearch(LocalDate.of(2026, 3, 5), listOf("a@b.cc")).wire())
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun theNumberOfMailsReadIsCapped() {
        val uids = (1L..30L).toList()
        val parts = ArrayList<ByteArray>()
        parts += b("* OK ready$crlf"); parts += b("Z001 OK done$crlf"); parts += b("* OK [UIDVALIDITY 1]$crlf" + "Z002 OK done$crlf")
        parts += b("* SEARCH ${uids.joinToString(" ")}$crlf" + "Z003 OK done$crlf")
        uids.forEach { parts += headerFetch(it, "alerts@hdfcbank.net") }
        parts += b("Z004 OK done$crlf")
        val top = uids.sortedDescending().take(3)
        var tag = 5
        top.forEach { parts += bodyFetch(it, upiMail); parts += b("Z00${tag++} OK done$crlf") }
        parts += b("Z00$tag OK bye$crlf")
        val limited = MailRequest(EmailProvider.GMAIL, "me@gmail.com", "abcdabcdabcdabcd", LocalDate.of(2026, 11, 1), listOf("alerts@hdfcbank.net"), 0, 0, maxMessages = 3)
        val batch = ImapMailbox.fetch(Script(*parts.toTypedArray()).session(), limited, EmailAlertSenders::isAllowed)
        assertEquals(top, batch.mails.map { it.uid })
    }
}
