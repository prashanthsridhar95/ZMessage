package com.zyke.message.domain.mms

import com.zyke.message.domain.classification.UpiLiteNotification
import com.zyke.message.domain.model.SmsMessage
import kotlin.math.abs

/**
 * One rich card, one message. Google Messages may keep a rich (RCS Business Messaging) card in the MMS provider while it also posts
 * one or two notifications for it (the text, and a second one for the picture). Zyke reads all of them, so without this the same
 * card showed up as three rows. Here the MMS row (authoritative: it has the real parts) and the notification items of the same
 * sender within [WINDOW_MS] become one message, before any message is classified, so a balance is read once.
 *
 * The merged message keeps the MMS id and date, carries the fullest text, and has both flags; the list tags it "RCS" when both are
 * set. Pure and idempotent: merging the result again changes nothing.
 */
object RichMerge {
    /** Notification time against MMS date: the two stamps of one card differ by seconds; three minutes is generous. */
    const val WINDOW_MS = 3 * 60 * 1000L

    /** What a placeholder-only notification is shown as when nothing it belongs to is found. */
    const val PICTURE_LABEL = "Picture"

    private val placeholders = setOf("image", "photo", "video", "sticker", "gif", "audio", "contact", "location", "picture", "picture message")

    /** The result: the messages to read, and which id was folded into which (dropped id to kept id) so the person's marks can follow. */
    class Result(val messages: List<SmsMessage>, val folded: Map<Long, Long>)

    /** "Image", "Photo", "📷 Photo", "Video" ...: a text that only names the kind of media. */
    fun isPlaceholder(body: String): Boolean {
        val letters = body.filter { it.isLetter() || it == ' ' }.trim().lowercase().replace(Regex("\\s+"), " ")
        return letters.isNotEmpty() && letters in placeholders
    }

    private fun key(sender: String): String = RbmSender.friendly(sender).lowercase().filter { it.isLetterOrDigit() }

    private fun isNotification(m: SmsMessage) = m.isRcs && !m.isMms && !UpiLiteNotification.isFromNotification(m.sender)

    fun apply(input: List<SmsMessage>): Result {
        val mms = input.filter { it.isMms }
        val notes = input.filter { isNotification(it) }
        if (mms.isEmpty() && notes.isEmpty()) return Result(input, emptyMap())

        // Each notification goes to the nearest MMS of the same sender within the window.
        val owner = HashMap<Long, SmsMessage>()
        for (n in notes) {
            val k = key(n.sender)
            val best = mms.filter { key(it.sender) == k && abs(it.date - n.date) <= WINDOW_MS }.minByOrNull { abs(it.date - n.date) }
            if (best != null) owner[n.id] = best
        }
        val attached = notes.filter { it.id in owner }.groupBy { owner.getValue(it.id).id }

        // Left-over placeholders fold into the nearest real notification of the same sender, else are only relabelled.
        val rest = notes.filter { it.id !in owner }
        val realRest = rest.filter { !isPlaceholder(it.body) }
        val folded = HashMap<Long, Long>()
        val dropped = HashSet<Long>()
        val relabel = HashMap<Long, SmsMessage>()
        for (n in rest.filter { isPlaceholder(it.body) }) {
            val k = key(n.sender)
            val host = realRest.filter { key(it.sender) == k && abs(it.date - n.date) <= WINDOW_MS }.minByOrNull { abs(it.date - n.date) }
            if (host != null) {
                dropped += n.id
                folded[n.id] = host.id
            } else {
                relabel[n.id] = n.copy(body = PICTURE_LABEL)
            }
        }

        val out = ArrayList<SmsMessage>(input.size)
        for (m in input) {
            when {
                m.id in owner -> {
                    dropped += m.id
                    folded[m.id] = owner.getValue(m.id).id
                }
                m.id in dropped -> Unit
                m.isMms -> out += merged(m, attached[m.id].orEmpty())
                else -> out += relabel[m.id] ?: m
            }
        }
        return Result(out.filter { it.id !in dropped }, folded)
    }

    private fun merged(m: SmsMessage, companions: List<SmsMessage>): SmsMessage {
        val rbm = RbmSender.isRbm(m.sender)
        val sender = if (rbm) RbmSender.friendly(m.sender) else m.sender
        val own = m.body.takeIf { it != MmsMessages.PICTURE_ONLY_TEXT }.orEmpty()
        val heard = companions.sortedBy { it.date }.map { it.body.trim() }.filter { it.isNotEmpty() && !isPlaceholder(it) }.distinct()
        val fullest = heard.filter { own.isEmpty() || it.contains(own) }.maxByOrNull { it.length }
        val body = when {
            fullest != null && fullest.length >= own.length -> fullest
            own.isNotEmpty() -> own
            else -> MmsMessages.PICTURE_ONLY_TEXT
        }
        val rich = rbm || companions.isNotEmpty()
        if (body == m.body && sender == m.sender && (!rich || m.isRcs)) return m
        return m.copy(sender = sender, body = body, isRcs = m.isRcs || rich)
    }
}
