package com.zyke.message.domain.nickname

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.model.ClassifiedMessage

/**
 * The nickname stage of the inbox pipeline, as pure functions: the pipeline uses [apply], and a screen that has just been
 * told about a new nickname uses [reapply] on the messages it already holds, so the name changes in the same frame as the tap
 * (see [PendingNicknames]). Both give the same result for the same nicknames.
 *
 * A name given to one payment wins over one given to everything from its payee (or sender). The nickname is [com.zyke.message.domain.model.Merchant.name];
 * what it replaced is kept in [com.zyke.message.domain.model.Merchant.originalName].
 */
object NicknameStage {

    /** The nickname in effect for [message] (its own first, then its payee's or sender's), and whether it is the message's own. */
    fun nicknameOf(message: ClassifiedMessage, nicknames: Map<String, String>): Pair<String, Boolean>? {
        if (nicknames.isEmpty()) return null
        nicknames[CategoryKey.messageKey(message.id)]?.let { return it to true }
        return CategoryKey.of(message)?.let(nicknames::get)?.let { it to false }
    }

    /** [message] as the pipeline has it before this stage (no nickname applied by it yet): the same instance for one that has none. */
    fun withoutNickname(message: ClassifiedMessage): ClassifiedMessage {
        val original = message.merchant.originalName ?: return message
        return message.copy(
            // A hand-made entry is named by the person from the start; that is its own name, not a nickname to take back.
            merchant = message.merchant.copy(name = original, renamed = ManualEntries.isManual(message), originalName = null),
            nicknameOwn = false
        )
    }

    /** Applies [nicknames] to a message that has none applied. The same instance when none matches. */
    fun apply(message: ClassifiedMessage, nicknames: Map<String, String>): ClassifiedMessage {
        val (nickname, own) = nicknameOf(message, nicknames) ?: return message
        return message.copy(
            merchant = message.merchant.copy(name = nickname, renamed = true, originalName = message.merchant.name),
            nicknameOwn = own
        )
    }

    /**
     * [message] as it reads with [nicknames] as they are now, whatever it showed before: a name given, changed, taken away
     * (back to the original) or moved from one level to the other. The same instance when nothing changes for it.
     */
    fun reapply(message: ClassifiedMessage, nicknames: Map<String, String>): ClassifiedMessage {
        val base = withoutNickname(message)
        val now = nicknameOf(base, nicknames)
        if (now == null) return base
        if (base !== message) {
            val shown = message.merchant
            if (shown.name == now.first && message.nicknameOwn == now.second) return message
        }
        return apply(base, nicknames)
    }

    /** [reapply] over a list: the very same list when no message changes, and unchanged messages stay the same instances. */
    fun reapplyAll(messages: List<ClassifiedMessage>, nicknames: Map<String, String>): List<ClassifiedMessage> {
        var out: ArrayList<ClassifiedMessage>? = null
        for (i in messages.indices) {
            val was = messages[i]
            val now = reapply(was, nicknames)
            if (now !== was) (out ?: ArrayList(messages).also { out = it })[i] = now
        }
        return out ?: messages
    }
}

/**
 * A nickname the person has just given, taken away or changed, shown on the screen's own messages in the same moment as the tap
 * instead of after the inbox pipeline has run again (which takes a while on a big inbox). For [window] after the change, every
 * list that arrives from the pipeline is brought up to the nicknames as they are now, so a list that was already on its way from
 * before the tap does not take the new name away. After the window the pipeline has certainly caught up and lists pass untouched.
 */
class PendingNicknames(private val nanoTime: () -> Long = System::nanoTime, private val windowNanos: Long = WINDOW_NANOS) {
    private var changedAt = 0L
    private var active = false

    /** The nicknames changed just now. */
    @Synchronized
    fun changed() {
        changedAt = nanoTime()
        active = true
    }

    /** [messages] with [nicknames] applied while a change is recent; otherwise the very same list. */
    @Synchronized
    fun overlay(messages: List<ClassifiedMessage>, nicknames: Map<String, String>): List<ClassifiedMessage> {
        if (!active) return messages
        if (nanoTime() - changedAt > windowNanos) { active = false; return messages }
        return NicknameStage.reapplyAll(messages, nicknames)
    }

    companion object {
        const val WINDOW_NANOS = 5_000_000_000L
    }
}
