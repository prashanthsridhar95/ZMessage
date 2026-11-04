package com.zyke.message.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.ledger.CardBillRepository
import com.zyke.message.domain.ledger.NoCardBills
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.ledger.ManualEntryRepository
import com.zyke.message.domain.ledger.SetAsideRepository
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CustomCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.nickname.NamingEdits
import com.zyke.message.domain.nickname.label
import kotlinx.coroutines.launch

/**
 * What can be done to a payment's place in the totals, from any screen that shows it: leave it out, call it a transfer
 * between the person's own accounts, or (for one entered by hand) delete it.
 */
class LedgerActions(
    private val asides: SetAsideRepository,
    private val entries: ManualEntryRepository,
    private val naming: NamingEdits? = null,
    private val cardBills: CardBillRepository = NoCardBills,
    /** Shows a message with an Undo button; null (the default) shows nothing. */
    private val offerUndo: (message: String, undo: () -> Unit) -> Unit = { _, _ -> }
) {
    /**
     * Flips "leave out of totals" from what is stored now (not from what the screen last drew, which can be one
     * change behind after a quick second tap). Returns whether the payment is left out afterwards.
     */
    fun toggleExcluded(id: Long): Boolean {
        val excluded = asides.toggleExcluded(id)
        com.zyke.message.data.diag.DevLog.d("ledger") { "toggle: id=$id leftOut=$excluded storedKind=${asides.state.value.kindOf(id)}" }
        offerUndo(if (excluded) "Left out of totals" else "Counted in totals again") { asides.exclude(id, !excluded) }
        return excluded
    }

    fun setExcluded(id: Long, excluded: Boolean) {
        asides.exclude(id, excluded)
        com.zyke.message.data.diag.DevLog.d("ledger") { "set: id=$id leftOut=$excluded storedKind=${asides.state.value.kindOf(id)}" }
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
            com.zyke.message.data.diag.DevLog.d("ledger") { "not a transfer: id=${message.id} hadLink=${previous != null} storedKind=${asides.state.value.kindOf(message.id)}" }
            offerUndo("Not a transfer") { previous?.let { asides.link(it.outId, it.inId) } }
            return
        }
        val received = (message.content as? MessageContent.Transaction)?.isReceived == true
        asides.link(outId = if (received) null else message.id, inId = if (received) message.id else null)
        com.zyke.message.data.diag.DevLog.d("ledger") { "mark transfer: id=${message.id} received=$received storedKind=${asides.state.value.kindOf(message.id)}" }
        offerUndo("Marked as a transfer") { asides.unlink(message.id) }
    }

    /**
     * Names and / or files payments in one step (the same way from every screen) and offers one Undo. A name already in
     * use brings its category along unless one is chosen in the request. [copyPicture] copies a picture from one key to
     * another; [removePicture] takes one away again on Undo. Returns null where naming is not available.
     */
    fun edit(
        request: NamingEdits.Request,
        messages: List<ClassifiedMessage>,
        pictureKeys: Set<String>,
        customs: List<CustomCategory>,
        copyPicture: (from: String, to: String) -> Unit = { _, _ -> },
        removePicture: (key: String) -> Unit = {},
        summary: String? = null
    ): NamingEdits.Outcome? {
        val edits = naming ?: return null
        val outcome = edits.apply(request, messages, pictureKeys)
        outcome.pictureCopies.forEach { (from, to) -> copyPicture(from, to) }
        com.zyke.message.data.diag.DevLog.d("ledger") {
            "edit: targets=${outcome.receipt.keys} renamed=${outcome.renamed} refiled=${outcome.refiled} inherited=${outcome.inherited != null} replacedOlder=${outcome.replacedOlder} pictures=${outcome.pictureCopies.size}"
        }
        val inherited = outcome.inherited
        val text = summary ?: when {
            inherited != null -> "Also set category to ${inherited.label(customs)} like your other \"${outcome.inheritedFrom}\" payments"
            else -> null
        }
        if (text != null && outcome.changedAnything) {
            offerUndo(text) {
                edits.undo(outcome.receipt)
                outcome.pictureCopies.forEach { (_, to) -> removePicture(to) }
            }
        }
        return outcome
    }

    /**
     * The answer to "is this a credit card bill payment?" for [messages]. Yes keeps them out of the totals (the swipes were
     * counted already) and, with [always], does the same for later payments to the same payees; No counts them as spending.
     * One Undo takes the whole answer back.
     */
    fun answerCardBill(messages: List<ClassifiedMessage>, isBillPayment: Boolean, always: Boolean) {
        if (messages.isEmpty()) return
        val keys = messages.associate { it.id to com.zyke.message.domain.categories.CategoryKey.of(it) }
        keys.forEach { (id, key) -> cardBills.answer(id, key, isBillPayment, always) }
        val many = messages.size > 1
        val text = when {
            isBillPayment -> if (many) "${messages.size} payments marked as credit card bill payments" else "Marked as a credit card bill payment"
            else -> if (many) "${messages.size} payments counted as spending" else "Counted as spending"
        }
        offerUndo(text) { keys.forEach { (id, key) -> cardBills.forget(id, key) } }
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
    val tick = rememberTick()
    return remember(container, snackbar, scope, tick) {
        LedgerActions(container.setAsides, container.manualEntries, container.namingEdits, container.cardBills) { message, undo ->
            scope.launch {
                // A newer change replaces an older one's offer rather than queueing behind it.
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) {
                    tick()
                    undo()
                }
            }
        }
    }
}
