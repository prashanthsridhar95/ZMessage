package com.zyke.message.domain.email

import java.time.LocalDate

/** Why a mailbox could not be read, in the words the Settings page can turn into one plain sentence. */
enum class MailProblem {
    /** The provider said the address or the app password is not right. */
    WRONG_PASSWORD,

    /** The provider says IMAP is off for this account, or that this kind of sign-in is not allowed. */
    PROVIDER_BLOCKED,

    /** No connection (no internet, the server did not answer, the connection dropped). */
    NETWORK,

    /** The secure connection could not be trusted (certificate or host name). Nothing is sent. */
    TLS,

    /** The server answered too slowly (15 seconds). */
    TIMEOUT,

    /** The server said something this client does not understand, or too much of it. */
    PROTOCOL,

    /** The address or password has characters that cannot be sent; nothing was sent. */
    BAD_INPUT
}

/** An IMAP step failed for a known [problem]. Never carries the command, the address or the password. */
class MailException(val problem: MailProblem) : Exception(problem.name)

/** One email that passed the sender list, ready for the alert parser: the text is already decoded and nothing else is kept. */
class RawMail(
    val uid: Long,
    /** The bare, lower-case address the mail came from. */
    val from: String,
    val subject: String,
    val dateMillis: Long,
    /** The Message-ID header, or empty. */
    val messageId: String,
    /** The readable text of the mail (plain part, else the page with its tags removed). */
    val text: String
)

/** What one pass over the mailbox found. */
class MailBatch(
    val uidValidity: Long,
    /** The highest UID looked at (found by the sender search); the next pass starts after it while [uidValidity] is unchanged. */
    val highestUid: Long,
    val mails: List<RawMail>,
    /** How many mails the sender search returned, before the checks on the sender header. */
    val searched: Int,
    /** How many of those were left out (not the listed address, or the provider said the sender check failed, or too many). */
    val skipped: Int
)

/** Everything one pass needs. The password is held only for the call. */
class MailRequest(
    val provider: EmailProvider,
    val address: String,
    val password: String,
    val since: LocalDate,
    val senders: List<String>,
    val knownUidValidity: Long,
    val afterUid: Long,
    val maxMessages: Int = MAX_MESSAGES,
    val maxBytesPerMail: Int = MAX_BYTES_PER_MAIL
) {
    companion object {
        const val MAX_MESSAGES = 100
        const val MAX_BYTES_PER_MAIL = 200 * 1024
    }
}

sealed interface MailResult<out T> {
    data class Ok<T>(val value: T) : MailResult<T>
    data class Failed(val problem: MailProblem) : MailResult<Nothing>
}

/**
 * The one door to a mailbox. The implementation (`ImapClient` in the app) is the only code that opens a connection; everything else
 * is written against this, so it is tested with a fake and never touches the network.
 */
interface MailSource {
    /** Signs in and out again; nothing is read. */
    fun check(provider: EmailProvider, address: String, password: String): MailResult<Unit>

    /** Signs in, finds the mails from [MailRequest.senders] since a date, reads those that pass, signs out. */
    fun fetch(request: MailRequest): MailResult<MailBatch>
}

/** A source that never connects: Demo mode and a build with the feature off. */
object NoMailSource : MailSource {
    override fun check(provider: EmailProvider, address: String, password: String): MailResult<Unit> = MailResult.Failed(MailProblem.NETWORK)
    override fun fetch(request: MailRequest): MailResult<MailBatch> = MailResult.Failed(MailProblem.NETWORK)
}
