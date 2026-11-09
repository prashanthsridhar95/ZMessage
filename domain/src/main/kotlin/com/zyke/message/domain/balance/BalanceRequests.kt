package com.zyke.message.domain.balance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the phone reported about a balance request text. */
enum class RequestStatus { SENDING, SENT, FAILED }

/** The last balance request made for one account (or the provident fund). Holds no number, no text and no UAN. */
data class BalanceRequest(val key: String, val sentAt: Long, val status: RequestStatus)

/** What a tile shows about its request. */
enum class RequestPhase { IDLE, SENDING, WAITING, NO_REPLY, FAILED }

object BalanceRequests {
    /** No more than one request per account in this time. */
    const val MIN_GAP_MS = 2L * 60_000L
    /** How long the tile says it is waiting for the reply. */
    const val WAIT_MS = 5L * 60_000L
    /** After that it says "No reply yet" for a while, then the note goes away by itself. */
    const val NO_REPLY_SHOWN_MS = 30L * 60_000L

    /** Milliseconds until another request for this account may be sent; 0 when it may be now. A request that failed does not count. */
    fun waitLeft(last: BalanceRequest?, now: Long): Long {
        if (last == null || last.status == RequestStatus.FAILED) return 0L
        return (last.sentAt + MIN_GAP_MS - now).coerceIn(0L, MIN_GAP_MS)
    }

    /**
     * Where the request stands. A balance that is newer than the request ([balanceAt] at or after [BalanceRequest.sentAt], epoch millis,
     * read from any message that states this account's balance) answers it, whatever number the bank replied from.
     */
    fun phase(request: BalanceRequest?, balanceAt: Long, now: Long): RequestPhase {
        if (request == null) return RequestPhase.IDLE
        if (request.status == RequestStatus.FAILED) return RequestPhase.FAILED
        if (balanceAt >= request.sentAt && request.status != RequestStatus.SENDING) return RequestPhase.IDLE
        val age = now - request.sentAt
        return when {
            request.status == RequestStatus.SENDING && age < MIN_GAP_MS -> RequestPhase.SENDING
            age < WAIT_MS -> RequestPhase.WAITING
            age < NO_REPLY_SHOWN_MS -> RequestPhase.NO_REPLY
            else -> RequestPhase.IDLE
        }
    }

    /** Shown beside a request that is waiting when Zyke cannot see chat (RCS) messages: the bank's answer may be one. */
    const val RICH_REPLY_HINT = "Reply may arrive as a rich message \u2014 turn on notification access so Zyke can read it"

    /** The same idea before a request is sent, in the Check balance sheet. */
    const val RICH_NOTE = "Some banks answer with a rich (RCS) message rather than a text. Zyke can read those only with notification access."

    fun statusLine(phase: RequestPhase): String? = when (phase) {
        RequestPhase.IDLE -> null
        RequestPhase.SENDING -> "Sending the request…"
        RequestPhase.WAITING -> "Request sent · waiting for the reply"
        RequestPhase.NO_REPLY -> "No reply yet"
        RequestPhase.FAILED -> "Couldn't send the request"
    }

    /** A report only moves a request forward: sending, then sent or failed; a late "sent" never undoes a failure. */
    fun advance(current: RequestStatus, incoming: RequestStatus): RequestStatus = when {
        current == RequestStatus.FAILED -> RequestStatus.FAILED
        incoming.ordinal >= current.ordinal -> incoming
        else -> current
    }
}

/** The person's changes to the presets, the saved UAN and the last request per account. */
interface BalanceCheckRepository {
    val setup: StateFlow<EnquirySetup>
    /** The UAN, digits only; "" when none is saved. */
    val uan: StateFlow<String>
    val requests: StateFlow<Map<String, BalanceRequest>>

    fun saveEdit(key: String, edit: EnquiryEdit)
    fun clearEdit(key: String)
    fun saveUan(uan: String)
    fun record(request: BalanceRequest)
    fun updateStatus(key: String, sentAt: Long, incoming: RequestStatus)
    fun clearRequest(key: String)
}

/** Demo mode and tests: everything is kept in memory only, nothing is saved, nothing can be sent from here. */
class InMemoryBalanceCheck : BalanceCheckRepository {
    private val _setup = MutableStateFlow(EnquirySetup())
    private val _uan = MutableStateFlow("")
    private val _requests = MutableStateFlow<Map<String, BalanceRequest>>(emptyMap())
    override val setup: StateFlow<EnquirySetup> = _setup
    override val uan: StateFlow<String> = _uan
    override val requests: StateFlow<Map<String, BalanceRequest>> = _requests

    override fun saveEdit(key: String, edit: EnquiryEdit) { _setup.value = EnquirySetup(_setup.value.edits + (key to edit)) }
    override fun clearEdit(key: String) { _setup.value = EnquirySetup(_setup.value.edits - key) }
    override fun saveUan(uan: String) { _uan.value = Uan.clean(uan) }
    override fun record(request: BalanceRequest) { _requests.value = _requests.value + (request.key to request) }
    override fun updateStatus(key: String, sentAt: Long, incoming: RequestStatus) {
        val current = _requests.value[key] ?: return
        if (current.sentAt != sentAt) return
        _requests.value = _requests.value + (key to current.copy(status = BalanceRequests.advance(current.status, incoming)))
    }
    override fun clearRequest(key: String) { _requests.value = _requests.value - key }
}
