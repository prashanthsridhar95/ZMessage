package com.zyke.message.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.ledger.ManualEntryRepository
import com.zyke.message.domain.ledger.SetAsideRepository
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

/**
 * What can be done to a payment's place in the totals, from any screen that shows it: leave it out, call it a transfer
 * between the person's own accounts, or (for one entered by hand) delete it.
 */
class LedgerActions(private val asides: SetAsideRepository, private val entries: ManualEntryRepository) {
    fun setExcluded(id: Long, excluded: Boolean) = asides.exclude(id, excluded)

    /** One leg of a transfer, seen alone: the other leg may never arrive as a message. */
    fun markTransfer(message: ClassifiedMessage, on: Boolean) {
        if (!on) {
            asides.unlink(message.id)
            return
        }
        val received = (message.content as? MessageContent.Transaction)?.isReceived == true
        asides.link(outId = if (received) null else message.id, inId = if (received) message.id else null)
    }

    fun deleteEntry(messageId: Long) {
        if (messageId < 0) entries.remove(ManualEntries.entryId(messageId))
    }
}

@Composable
fun rememberLedgerActions(): LedgerActions {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    return remember(container) { LedgerActions(container.setAsides, container.manualEntries) }
}
