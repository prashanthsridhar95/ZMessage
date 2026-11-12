package com.zyke.message.domain.sharing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The person's code-sharing setup and the log of what was sent. Kept in private preferences; part of a backup (the log is not). */
interface CodeSharingRepository {
    val rules: StateFlow<CodeSharingRules>
    val log: StateFlow<List<ShareLogEntry>>

    /** The last [SeenCodes.MAX_ENTRIES] code-like messages the live receiver handled, and why each was or was not passed on. Not part of a backup. */
    val seen: StateFlow<List<SeenCode>>

    /** When the receiver last heard any text (a time only), or 0. Not part of a backup. */
    val lastReceiverAt: StateFlow<Long>

    fun setEnabled(on: Boolean)
    fun setPausedUntil(at: Long)
    fun setWindow(window: ShareWindow?)
    fun setSenderName(name: String)

    /** Adds the person, or replaces the one with the same id. */
    fun saveContact(contact: TrustedContact)
    fun removeContact(id: String)
    fun setContactEnabled(id: String, on: Boolean)

    fun addLog(entry: ShareLogEntry)
    fun updateStatus(id: String, incoming: ShareStatus)
    fun clearLog()

    fun addSeen(entry: SeenCode)
    fun clearSeen()
    fun noteReceiverFired(at: Long)

    /** Teaches a sender name ([ShareServices.senderToken]) as a service's; checked by [ShareLearning.problem] by the caller. */
    fun learnHeader(serviceId: String, token: String)
    fun forgetHeader(serviceId: String, token: String)

    /** What was sent lately: the saved log, plus the code keys held in memory since this process started. */
    fun history(now: Long): ShareHistory

    /** Remembers a send for the duplicate check, in memory only. */
    fun remember(record: SentRecord)
}

/** Demo mode and tests: nothing is on, nothing is kept. */
object NoCodeSharing : CodeSharingRepository {
    override val rules: StateFlow<CodeSharingRules> = MutableStateFlow(CodeSharingRules())
    override val log: StateFlow<List<ShareLogEntry>> = MutableStateFlow(emptyList())
    override val seen: StateFlow<List<SeenCode>> = MutableStateFlow(emptyList())
    override val lastReceiverAt: StateFlow<Long> = MutableStateFlow(0L)
    override fun setEnabled(on: Boolean) = Unit
    override fun setPausedUntil(at: Long) = Unit
    override fun setWindow(window: ShareWindow?) = Unit
    override fun setSenderName(name: String) = Unit
    override fun saveContact(contact: TrustedContact) = Unit
    override fun removeContact(id: String) = Unit
    override fun setContactEnabled(id: String, on: Boolean) = Unit
    override fun addLog(entry: ShareLogEntry) = Unit
    override fun updateStatus(id: String, incoming: ShareStatus) = Unit
    override fun clearLog() = Unit
    override fun addSeen(entry: SeenCode) = Unit
    override fun clearSeen() = Unit
    override fun noteReceiverFired(at: Long) = Unit
    override fun learnHeader(serviceId: String, token: String) = Unit
    override fun forgetHeader(serviceId: String, token: String) = Unit
    override fun history(now: Long): ShareHistory = ShareHistory()
    override fun remember(record: SentRecord) = Unit
}
