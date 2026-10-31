package com.zyke.message.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.ledger.ManualEntryRepository
import com.zyke.message.domain.ledger.SetAsideRepository
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import kotlinx.coroutines.launch

/**
 * What can be done to a payment's place in the totals, from any screen that shows it: leave it out, call it a transfer
 * between the person's own accounts, or (for one entered by hand) delete it.
 */
class LedgerActions(
    private val asides: SetAsideRepository,
    private val entries: ManualEntryRepository,
    /** Shows a message with an Undo button; null (the default) shows nothing. */
    private val offerUndo: (message: String, undo: () -> Unit) -> Unit = { _, _ -> }
) {
    fun setExcluded(id: Long, excluded: Boolean) {
        asides.exclude(id, excluded)
        offerUndo(if (excluded) "Left out of totals" else "Counted in totals again") { asides.exclude(id, !excluded) }
    }

    /** Shows [message] with an Undo that runs [undo]; for the pages that change the ledger through other paths. */
    fun withUndo(message: String, undo: () -> Unit) = offerUndo(message, undo)

    /** Brings back a transfer suggestion the person dismissed. */
    fun restoreSuggestion(key: String) = asides.restoreSuggestion(key)

    /** Takes a transfer back out; for the Undo of linking one. */
    fun unlinkTransfer(debitId: Long?, creditId: Long?) {
        debitId?.let { asides.unlink(it) }
        creditId?.let { asides.unlink(it) }
    }

    /** One leg of a transfer, seen alone: the other leg may never arrive as a message. */
    fun markTransfer(message: ClassifiedMessage, on: Boolean) {
        if (!on) {
            val previous = asides.state.value.transfers.firstOrNull { it.outId == message.id || it.inId == message.id }
            asides.unlink(message.id)
            offerUndo("Not a transfer") { previous?.let { asides.link(it.outId, it.inId) } }
            return
        }
        val received = (message.content as? MessageContent.Transaction)?.isReceived == true
        asides.link(outId = if (received) null else message.id, inId = if (received) message.id else null)
        offerUndo("Marked as a transfer") { asides.unlink(message.id) }
    }

    fun deleteEntry(messageId: Long) {
        if (messageId < 0) entries.remove(ManualEntries.entryId(messageId))
    }
}

@Composable
fun rememberLedgerActions(): LedgerActions {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(container, snackbar, scope) {
        LedgerActions(container.setAsides, container.manualEntries) { message, undo ->
            scope.launch {
                // A newer change replaces an older one's offer rather than queueing behind it.
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) undo()
            }
        }
    }
}
