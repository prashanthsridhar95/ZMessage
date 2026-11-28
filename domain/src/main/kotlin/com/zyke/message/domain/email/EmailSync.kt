package com.zyke.message.domain.email

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The answer to Connect. */
sealed interface ConnectResult {
    data object Connected : ConnectResult
    data class Failed(val problem: MailProblem, val sentence: String) : ConnectResult
}

/**
 * Connects a mailbox, reads it when asked or when the app opens, and lets go of it. It is the only thing that turns what a [MailSource]
 * returns into alerts, and it knows nothing about sockets. One pass at a time; no timer; nothing runs unless the person switched
 * email alerts on and connected. A pass never changes the mailbox, and what it keeps is the parsed fields and a masked snippet.
 */
class EmailSync(
    private val repository: EmailAlertRepository,
    private val credentials: EmailCredentialStore,
    private val source: MailSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val parser: EmailAlertParser = EmailAlertParser()
) {
    private val gate = Mutex()
    private val running = MutableStateFlow(false)

    /** True while a pass is under way (for the Sync now button). */
    val busy: StateFlow<Boolean> = running

    /** Tries the sign-in; only if it works are the password and the choices saved. */
    suspend fun connect(provider: EmailProvider, address: String, password: String): ConnectResult = gate.withLock {
        running.value = true
        try {
            val a = EmailInput.cleanAddress(address)
            val p = EmailInput.cleanPassword(password)
            EmailInput.problem(a, p)?.let { return ConnectResult.Failed(MailProblem.BAD_INPUT, it) }
            val result = withContext(io) { source.check(provider, a, p) }
            if (result is MailResult.Failed) return ConnectResult.Failed(result.problem, sentenceOf(result.problem))
            if (!credentials.save(p)) return ConnectResult.Failed(MailProblem.BAD_INPUT, "The password could not be kept safely on this phone.")
            repository.update {
                it.copy(
                    enabled = true, explained = true, providerId = provider.id, address = a, connected = true, lastOutcome = SyncOutcome.OK,
                    uidValidity = 0L, highestUid = 0L, lastSyncAt = 0L, lastAdded = 0
                )
            }
            ConnectResult.Connected
        } finally {
            running.value = false
        }
    }

    /** Reads the mailbox now. Returns what the pass ended with, or null when email alerts are off or not connected. */
    suspend fun syncNow(): SyncOutcome? = gate.withLock {
        val settings = repository.state.value.settings
        if (!settings.enabled || !settings.connected) return null
        val provider = settings.provider ?: return null
        val password = credentials.password()
        val now = clock()
        if (password == null) return finish(settings, now, SyncOutcome.WRONG_PASSWORD, 0, settings.uidValidity, settings.highestUid, lostPassword = true)
        running.value = true
        try {
            val request = MailRequest(
                provider = provider, address = settings.address, password = password, since = sinceDate(settings, now),
                senders = EmailAlertSenders.addresses, knownUidValidity = settings.uidValidity, afterUid = settings.highestUid
            )
            when (val result = withContext(io) { source.fetch(request) }) {
                is MailResult.Failed -> finish(settings, now, outcomeOf(result.problem), 0, settings.uidValidity, settings.highestUid)
                is MailResult.Ok -> {
                    val alerts = readAll(provider, result.value, now)
                    repository.add(alerts, now)
                    finish(settings, now, SyncOutcome.OK, alerts.size, result.value.uidValidity, result.value.highestUid)
                }
            }
        } finally {
            running.value = false
        }
    }

    /** [syncNow] when the person's choice says it is time: when the app opens, never while "only when I tap" is chosen. */
    suspend fun syncIfDue(): SyncOutcome? {
        val s = repository.state.value.settings
        if (!s.enabled || !s.connected || s.syncEvery == EmailSyncEvery.MANUAL) return null
        val gap = s.syncEvery.minGapMs
        val since = clock() - s.lastSyncAt
        if (s.lastSyncAt != 0L && since in 0 until gap) return null
        repository.prune(clock())
        return syncNow()
    }

    /** Forgets the password, the connection and every kept alert. The mailbox itself is never touched. */
    fun disconnect() {
        credentials.clear()
        repository.clearAll()
    }

    /** Removes the kept alerts but stays connected; the next pass reads the mailbox again. */
    fun deleteAlerts() = repository.clearAlerts()

    private fun readAll(provider: EmailProvider, batch: MailBatch, now: Long): List<EmailAlert> {
        val out = ArrayList<EmailAlert>()
        for (mail in batch.mails) {
            val sender = EmailAlertSenders.find(mail.from) ?: continue
            val key = EmailAlerts.keyOf(provider.id, batch.uidValidity, mail.uid, mail.messageId)
            val at = if (mail.dateMillis > 0L && mail.dateMillis <= now + DAY_MS) mail.dateMillis else now
            when (val read = parser.read(sender, mail.subject, mail.text, at, key)) {
                is EmailRead.Understood -> out += read.alert
                is EmailRead.NotUnderstood -> out += read.alert
                is EmailRead.Skipped -> Unit
            }
        }
        return out
    }

    private fun finish(
        before: EmailAlertSettings, now: Long, outcome: SyncOutcome, added: Int, uidValidity: Long, highestUid: Long, lostPassword: Boolean = false
    ): SyncOutcome {
        repository.update {
            it.copy(
                lastSyncAt = now, lastOutcome = outcome, lastAdded = if (outcome == SyncOutcome.OK) added else it.lastAdded,
                uidValidity = if (outcome == SyncOutcome.OK) uidValidity else before.uidValidity,
                highestUid = if (outcome == SyncOutcome.OK) highestUid else before.highestUid,
                // A password the provider refused, or one the phone can no longer open, is asked for again.
                connected = if (outcome == SyncOutcome.WRONG_PASSWORD || lostPassword) false else it.connected
            )
        }
        return outcome
    }

    private fun sinceDate(s: EmailAlertSettings, now: Long): LocalDate {
        val keepFloor = now - s.keepMonths.coerceAtLeast(1) * DAYS_PER_MONTH * DAY_MS
        val from = if (s.lastSyncAt == 0L) now - FIRST_DAYS * DAY_MS else s.lastSyncAt - OVERLAP_DAYS * DAY_MS
        return Instant.ofEpochMilli(maxOf(from, keepFloor)).atZone(zone).toLocalDate()
    }

    private fun outcomeOf(p: MailProblem): SyncOutcome = when (p) {
        MailProblem.WRONG_PASSWORD -> SyncOutcome.WRONG_PASSWORD
        MailProblem.PROVIDER_BLOCKED -> SyncOutcome.PROVIDER_BLOCKED
        MailProblem.NETWORK -> SyncOutcome.NETWORK
        MailProblem.TLS -> SyncOutcome.TLS
        MailProblem.TIMEOUT -> SyncOutcome.TIMEOUT
        MailProblem.PROTOCOL, MailProblem.BAD_INPUT -> SyncOutcome.PROTOCOL
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val DAYS_PER_MONTH = 30L
        private const val FIRST_DAYS = 60L
        private const val OVERLAP_DAYS = 3L

        /** One plain sentence for a failed Connect. */
        fun sentenceOf(p: MailProblem): String = when (p) {
            MailProblem.WRONG_PASSWORD -> "The provider did not accept that address and app password. Check both; use an app password, not your normal one."
            MailProblem.PROVIDER_BLOCKED -> "The provider refused the sign-in. IMAP may be switched off for this account, or it does not allow app passwords."
            MailProblem.NETWORK -> "Could not reach the provider. Check your internet connection and try again."
            MailProblem.TLS -> "The secure connection could not be trusted, so nothing was sent."
            MailProblem.TIMEOUT -> "The provider took too long to answer. Try again."
            MailProblem.PROTOCOL -> "The provider answered in a way this app does not understand."
            MailProblem.BAD_INPUT -> "That address or password cannot be used."
        }

        /** The same for the status card of the last pass. */
        fun sentenceOf(o: SyncOutcome?): String = when (o) {
            null -> ""
            SyncOutcome.OK -> "Up to date"
            SyncOutcome.WRONG_PASSWORD -> "The provider did not accept the saved app password. Connect again."
            SyncOutcome.PROVIDER_BLOCKED -> "The provider refused the sign-in."
            SyncOutcome.NETWORK -> "No connection to the provider at the last try."
            SyncOutcome.TLS -> "The secure connection could not be trusted at the last try."
            SyncOutcome.TIMEOUT -> "The provider was too slow at the last try."
            SyncOutcome.PROTOCOL -> "The provider answered in a way this app does not understand."
        }
    }
}
