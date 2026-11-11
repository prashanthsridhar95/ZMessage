package com.zyke.message.data.diag

import com.zyke.message.di.AppContainer
import com.zyke.message.domain.diagnostics.InboxAudit
import com.zyke.message.domain.diagnostics.InboxAuditInput
import com.zyke.message.domain.diagnostics.InboxAuditReport
import com.zyke.message.domain.feed.MessageFilter
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Developer option (Settings, developer options): follows every message on the phone from the SMS and MMS providers and the stored chat
 * notifications to the All tab and counts what became of each. Runs only when asked, reads the inbox once, keeps no message text, and
 * is never available in Demo mode. Nothing is written to the log: the report is shown on screen and shared only by the person, with
 * senders masked.
 */
object InboxAuditRunner {
    private const val WAIT_MS = 6_000L

    /** Null in Demo mode, without the SMS permission, or when the phone's provider cannot be read. */
    suspend fun run(container: AppContainer, filter: MessageFilter = MessageFilter.All): InboxAuditReport? = withContext(Dispatchers.IO) {
        if (container.demo) return@withContext null
        val zone = ZoneId.systemDefault()
        val snapshot = try {
            container.smsDataSource.auditSnapshot(zone)
        } catch (denied: SecurityException) {
            return@withContext null
        }
        val messages = withTimeoutOrNull(WAIT_MS) { container.observeMessages().first() }.orEmpty()
        InboxAudit.build(
            InboxAuditInput(
                provider = snapshot,
                messages = messages,
                foldedInto = container.observeMessages.foldedInto(),
                copiedInto = container.observeMessages.copiedInto(),
                userMerges = container.merges.merges.value,
                userState = container.userMessageState.state.value,
                filter = filter,
                progress = container.observeMessages.progress.value,
                now = Clock.systemDefaultZone().instant(),
                zone = zone
            )
        )
    }
}
