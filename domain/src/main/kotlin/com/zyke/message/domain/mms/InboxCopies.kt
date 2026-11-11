package com.zyke.message.domain.mms

import com.zyke.message.domain.classification.UpiLiteNotification
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.model.SmsMessage
import kotlin.math.abs

/**
 * One text, one message, for copies of the SAME text that the phone delivers twice. It never looks at what a message is worth:
 * it only folds messages that are provably one text, before anything is classified. Every inbox SMS is otherwise its own message
 * (five alike alerts a minute apart are five payments), so each rule below needs positive evidence, and the SMS from the
 * provider is always the one that stays (a copy never absorbs the SMS). A folded message is not deleted from the phone; it just
 * is not a second row (its id goes into [RichMerge.Result.folded] so the person's marks follow it, and the inbox audit shows it).
 *
 * 1. A chat (RCS) notification item, or a picture-message (MMS provider) row, that repeats an SMS: the same normalised sender and
 *    the same normalised text (letters and digits only, any case), or one text containing the other when the shorter is at least
 *    [MIN_TEXT] characters (a notification shows a cut-off or prefixed text), within [COPY_WINDOW_MS]. Each SMS absorbs one copy.
 * 2. A provider double insert: two SMS rows with the same address and the same body whose dates are identical to the millisecond
 *    (two real alerts are never stamped in the same millisecond), or within one second when the body carries a transaction
 *    reference number (a reference is unique to one transaction). A date with no milliseconds at all (a phone that stamps whole
 *    seconds) cannot show that two rows were the same instant, so without a reference it never folds. The earlier row stays.
 *
 * Two alerts a minute apart with the same words and no reference stay two messages. The same transaction reference arriving
 * minutes or hours apart is not folded here: it stays two messages and [SamePayment] links them (one counted, the other inside its row).
 * Pure and idempotent.
 */
object InboxCopies {
    /** A chat notification or a picture message is the same text as an SMS when it arrives this close to it. */
    const val COPY_WINDOW_MS = 10 * 60 * 1000L

    /** Provider rows with a shared transaction reference are one text when stamped this close. */
    const val DOUBLE_INSERT_REF_MS = 1000L

    /** The shortest text (letters and digits) that a containing text can be called a copy of. */
    const val MIN_TEXT = 20

    private fun senderKey(sender: String): String = SenderKey.of(sender).lowercase().filter { it.isLetterOrDigit() }

    private fun textKey(body: String): String = body.lowercase().filter { it.isLetterOrDigit() }

    private fun sameText(a: String, b: String): Boolean {
        if (a == b) return a.length >= MIN_TEXT
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= MIN_TEXT && longer.contains(shorter)
    }

    private fun isSms(m: SmsMessage) = !m.isMms && !m.isRcs

    private fun isCopyCandidate(m: SmsMessage) =
        (m.isMms && !m.isRcs) || (m.isRcs && !m.isMms && !UpiLiteNotification.isFromNotification(m.sender))

    fun apply(input: List<SmsMessage>): RichMerge.Result {
        if (input.size < 2) return RichMerge.Result(input, emptyMap())
        val folded = HashMap<Long, Long>()

        // 2. provider double inserts (first, so that a copy folds into the SMS that stays)
        val bySameText = input.filter { isSms(it) }.groupBy { it.sender.trim() to it.body }
        for ((_, rows) in bySameText) {
            if (rows.size < 2) continue
            val ordered = rows.sortedWith(compareBy<SmsMessage> { it.date }.thenBy { it.id })
            var keep = ordered.first()
            for (n in ordered.drop(1)) {
                val gap = abs(n.date - keep.date)
                val sameInstant = gap == 0L && n.date % 1000L != 0L
                val sameReference = gap <= DOUBLE_INSERT_REF_MS && SamePayment.transactionRefs(n.body).isNotEmpty()
                if (sameInstant || sameReference) folded[n.id] = keep.id else keep = n
            }
        }

        // 1. chat notifications and picture-message rows that repeat an SMS
        val copies = input.filter { isCopyCandidate(it) }
        if (copies.isNotEmpty()) {
            val smsRows = input.filter { isSms(it) && it.id !in folded }
            val keys = HashMap<Long, Pair<String, String>>()
            for (s in smsRows) keys[s.id] = senderKey(s.sender) to textKey(s.body)
            val taken = HashSet<Long>()
            for (c in copies.sortedWith(compareBy<SmsMessage> { it.date }.thenBy { it.id })) {
                val sender = senderKey(c.sender)
                val text = textKey(c.body)
                if (sender.isEmpty() || text.length < MIN_TEXT) continue
                val host = smsRows
                    .filter { s ->
                        s.id !in taken && abs(s.date - c.date) <= COPY_WINDOW_MS &&
                            keys.getValue(s.id).let { (k, t) -> k == sender && sameText(t, text) }
                    }
                    .minWithOrNull(compareBy<SmsMessage> { abs(it.date - c.date) }.thenBy { it.id }) ?: continue
                taken += host.id
                folded[c.id] = host.id
            }
        }

        if (folded.isEmpty()) return RichMerge.Result(input, emptyMap())
        return RichMerge.Result(input.filter { it.id !in folded }, folded)
    }
}
