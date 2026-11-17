package com.zyke.message.presentation.common

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.PendingCategories
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.MutedSenderRepository
import com.zyke.message.domain.repository.UserMessageStateRepository

/**
 * The message-level choices Home and Messages both offer (pin, mute, archive, read on copy, open, dismiss, file under a category),
 * written once. A screen keeps what is its own (its effects, its detail sheet, its swipe actions) and calls this for the rest.
 *
 * [write] runs a save: Home queues it for its writer thread, Messages runs it at once. [showOverlay] applies [pending] on the
 * screen's own state in one reduction. Nothing here touches the main thread's state except through those two.
 */
class MessageActions(
    private val userState: UserMessageStateRepository,
    private val categoryOverrides: CategoryOverrideRepository,
    private val mutedSenders: MutedSenderRepository,
    /** Names the screen in the developer log ("home", "messages"). */
    private val screen: String,
    private val messages: () -> List<ClassifiedMessage>,
    private val pinnedIds: () -> Set<Long>,
    private val write: (() -> Unit) -> Unit,
    private val showOverlay: (PendingCategories) -> Unit
) {
    /** Category choices shown at once, until the inbox that comes back carries them (see [PendingCategories]). */
    val pending = PendingCategories()

    fun pin(messageId: Long) {
        val pin = messageId !in pinnedIds()
        write { userState.setPinned(messageId, pin) }
    }

    fun toggleMute(messageId: Long) {
        messages().firstOrNull { it.id == messageId }?.let { message ->
            val sender = SenderKey.of(message.message.sender)
            val mute = !message.isMuted
            write { mutedSenders.setMuted(sender, mute) }
        }
    }

    fun archive(messageId: Long) = write { userState.setArchived(messageId, true) }

    fun unarchive(messageId: Long) = write { userState.setArchived(messageId, false) }

    /** Copying a code reads the message it came from. */
    fun markReadForCopy(messageId: Long) = write { userState.markRead(messageId) }

    fun dismissAttention(key: String, forever: Boolean) = write { userState.dismissAttention(key, forever) }

    /** Opening a payment opens the messages that repeat it too: the group is one row, so it is read as one. */
    fun markOpened(messageId: Long) {
        val repeats = com.zyke.message.domain.ledger.PaymentGroups.of(messages()).followersOf(messageId)
        write { userState.markRead(messageId); repeats.forEach { userState.markRead(it.id) } }
    }

    fun setMessageCategory(messageId: Long, category: MessageCategory?) {
        com.zyke.message.data.diag.DevLog.d("category") { "$screen: this transaction id=$messageId -> $category" }
        val key = CategoryKey.messageKey(messageId)
        showCategoryNow(key, category)
        write { categoryOverrides.set(key, category) }
    }

    /**
     * Files every payment of a sender or receiver. [onFiled] gets the message and the choice it had before (null when none was
     * the person's), once the choice is shown and handed to the write.
     */
    fun setCategory(messageId: Long, category: MessageCategory?, onFiled: (ClassifiedMessage, MessageCategory?) -> Unit = { _, _ -> }) {
        val message = messages().firstOrNull { it.id == messageId }
        val key = message?.let(CategoryKey::of)
        com.zyke.message.data.diag.DevLog.d("category") {
            "$screen: sender/receiver id=$messageId -> $category messageFound=${message != null} key=${com.zyke.message.domain.diag.DiagLog.key(key)} messagesInState=${messages().size}"
        }
        if (message == null || key == null) return
        val previous = message.category.takeIf { message.categoryOverridden }
        showCategoryNow(key, category)
        write { categoryOverrides.set(key, category) }
        onFiled(message, previous)
    }

    /**
     * The person filed a payment or a payee: the messages in the state show it in this same reduction, before the choice has gone
     * through the pipeline. Always before the write.
     */
    private fun showCategoryNow(key: String, category: MessageCategory?) {
        val started = System.nanoTime()
        pending.add(key, category)
        showOverlay(pending)
        com.zyke.message.domain.diag.DiagLog.d("category") { "change shown in ${(System.nanoTime() - started) / 1_000_000} ms (${messages().size} messages)" }
    }
}
