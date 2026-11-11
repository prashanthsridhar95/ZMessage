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

/** What the "Check balance" slot on a tile shows: the button, a progress in the button's place, a brief confirmation, or a retry. */
enum class CheckState { READY, CHECKING, UPDATED, NO_REPLY, FAILED }

/** What a tap on "Check balance" does. */
enum class TapAction { SEND_NOW, CONFIRM_FIRST }

/**
 * The one-tap flow. The first check for a bank shows the confirm sheet (number, exact words, rates note) with "Always send without
 * asking for this bank" ticked; once the person has confirmed that, a tap sends at once and the button turns into a progress in the
 * same place until the reply updates the balance or [REPLY_TIMEOUT_MS] passes. Pure, so every state is tested.
 */
object BalanceCheckFlow {
    /** How long the slot shows "Checking…" before it offers "No reply yet · Try again". */
    const val REPLY_TIMEOUT_MS = 2L * 60_000L
    /** How long "Updated just now" stays after the reply, before the normal "As of" line is all that is left. */
    const val UPDATED_MS = 5_000L

    /** A tap sends at once only when the bank was confirmed before AND the plan is complete; anything else shows the sheet. */
    fun onTap(skipConfirm: Boolean, plan: EnquiryPlan): TapAction =
        if (skipConfirm && plan is EnquiryPlan.Ready) TapAction.SEND_NOW else TapAction.CONFIRM_FIRST

    fun state(request: BalanceRequest?, balanceAt: Long, now: Long): CheckState {
        if (request == null) return CheckState.READY
        if (request.status == RequestStatus.FAILED) return CheckState.FAILED
        if (balanceAt >= request.sentAt && request.status != RequestStatus.SENDING) {
            return if (now - balanceAt in 0L..UPDATED_MS) CheckState.UPDATED else CheckState.READY
        }
        val age = now - request.sentAt
        return when {
            age < REPLY_TIMEOUT_MS -> CheckState.CHECKING
            age < BalanceRequests.NO_REPLY_SHOWN_MS -> CheckState.NO_REPLY
            else -> CheckState.READY
        }
    }

    /** The label of the slot for a state; null in READY (the normal button label is shown). */
    fun label(state: CheckState): String? = when (state) {
        CheckState.READY -> null
        CheckState.CHECKING -> "Checking\u2026"
        CheckState.UPDATED -> "Updated just now"
        CheckState.NO_REPLY, CheckState.FAILED -> "Try again"
    }

    /** The short line beside the "Try again" button; null when the slot says everything itself. */
    fun note(state: CheckState): String? = when (state) {
        CheckState.NO_REPLY -> "No reply yet"
        CheckState.FAILED -> "Couldn't send the request"
        else -> null
    }

    /** True while the slot must keep ticking (a progress or a brief confirmation is showing). */
    fun ticking(state: CheckState): Boolean = state == CheckState.CHECKING || state == CheckState.UPDATED
}

/** The person's changes to the presets, the saved UAN and the last request per account. */
interface BalanceCheckRepository {
    val setup: StateFlow<EnquirySetup>
    /** The UAN, digits only; "" when none is saved. */
    val uan: StateFlow<String>
    val requests: StateFlow<Map<String, BalanceRequest>>
    /** Bank keys (see [BalanceEnquiry.bankKey]) the person confirmed once with "Always send without asking"; a tap sends at once. */
    val skipConfirm: StateFlow<Set<String>>

    fun setSkipConfirm(key: String, skip: Boolean)
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
    private val _skip = MutableStateFlow<Set<String>>(emptySet())
    override val skipConfirm: StateFlow<Set<String>> = _skip
    override fun setSkipConfirm(key: String, skip: Boolean) { _skip.value = if (skip) _skip.value + key else _skip.value - key }

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
