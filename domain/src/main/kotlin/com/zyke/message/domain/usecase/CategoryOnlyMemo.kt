package com.zyke.message.domain.usecase

import com.zyke.message.domain.model.ClassifiedMessage

/**
 * Remembers what one stage of the inbox pipeline made of its input, so that when only the CATEGORY of a few messages
 * changed (the person filed a payment, a payee or a word) the stage's result is the old result with those few messages'
 * category fields replaced, instead of the whole stage run again over every message of the inbox.
 *
 * This is exact only for a stage that reads a message's category in no way except through [sensitivity]: it is built from the old input and given the
 * old and the new version of every changed message, and the stage is run in full when the two answers differ (a stage
 * that does not look at categories at all passes the default, which never differs). A stage that is not given the same
 * [run] token (its own settings), a different set of messages, or a change in any other field is run in full.
 */
class CategoryOnlyMemo(private val sensitivityFor: ((List<ClassifiedMessage>) -> ((ClassifiedMessage) -> Int))? = null) {
    private var previousIn: List<ClassifiedMessage>? = null
    private var previousOut: List<ClassifiedMessage>? = null
    private var previousToken: Any? = null
    private var indexOfOut: Map<Long, Int>? = null

    /** How many times the stage was run in full, and how many times it was patched; read by tests. */
    var fullRuns = 0
        private set
    var patchedRuns = 0
        private set

    fun run(input: List<ClassifiedMessage>, token: Any?, compute: (List<ClassifiedMessage>) -> List<ClassifiedMessage>): List<ClassifiedMessage> {
        val before = previousIn
        val result = before?.let { old -> previousOut?.takeIf { previousToken === token || previousToken == token }?.let { patched(old, it, input) } }
        if (result != null) {
            patchedRuns++
        } else {
            fullRuns++
        }
        val out = result ?: compute(input)
        if (out !== previousOut) indexOfOut = null
        previousIn = input
        previousOut = out
        previousToken = token
        return out
    }

    private fun patched(old: List<ClassifiedMessage>, oldOut: List<ClassifiedMessage>, input: List<ClassifiedMessage>): List<ClassifiedMessage>? {
        if (old.size != input.size) return null
        var changed: ArrayList<ClassifiedMessage>? = null
        var sensitivity: ((ClassifiedMessage) -> Int)? = null
        for (i in input.indices) {
            val was = old[i]
            val now = input[i]
            if (was === now) continue
            if (was.id != now.id) return null
            if (was == now) continue
            if (!CategoryFields.onlyCategoryDiffers(was, now)) return null
            if (sensitivityFor != null) {
                val read = sensitivity ?: sensitivityFor.invoke(old).also { sensitivity = it }
                if (read(was) != read(now)) return null
            }
            (changed ?: ArrayList<ClassifiedMessage>().also { changed = it }) += now
        }
        val list = changed ?: return oldOut
        val index = indexOfOut ?: HashMap<Long, Int>(oldOut.size * 2).also { map -> oldOut.forEachIndexed { i, m -> map[m.id] = i }; indexOfOut = map }
        val out = ArrayList(oldOut)
        for (now in list) {
            val at = index[now.id] ?: continue
            out[at] = CategoryFields.copyInto(out[at], now)
        }
        return out
    }
}

/** The fields that say which category a message is filed under, and nothing else. */
object CategoryFields {
    /** True when [a] and [b] are the same message but for the category fields. */
    fun onlyCategoryDiffers(a: ClassifiedMessage, b: ClassifiedMessage): Boolean = copyInto(a, b) == b

    /** [target] with the category fields of [source]. */
    fun copyInto(target: ClassifiedMessage, source: ClassifiedMessage): ClassifiedMessage =
        if (target.category == source.category && target.merchant.category == source.merchant.category &&
            target.categoryOverridden == source.categoryOverridden && target.categoryOwn == source.categoryOwn &&
            target.customCategoryId == source.customCategoryId
        ) {
            target
        } else {
            target.copy(
                category = source.category,
                merchant = target.merchant.copy(category = source.merchant.category),
                categoryOverridden = source.categoryOverridden,
                categoryOwn = source.categoryOwn,
                customCategoryId = source.customCategoryId
            )
        }
}
