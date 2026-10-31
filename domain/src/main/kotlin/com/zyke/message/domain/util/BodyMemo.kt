package com.zyke.message.domain.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Remembers what was worked out from a message body, so a screen rebuilt because something else changed (a payment set
 * aside, two messages merged) does not read every body with a regular expression again. On Android each regex match
 * allocates native memory that is only given back when the garbage collector runs; thousands of matches per rebuild
 * can outrun it.
 *
 * The answer must depend on the body alone. It is found by the message id and confirmed against the body text, so a
 * message whose text changed, or an id reused for another message, is simply worked out again.
 */
class BodyMemo<T : Any>(private val limit: Int = DEFAULT_LIMIT, private val compute: (String) -> T) {
    private class Entry<T>(val body: String, val value: T)

    private val entries = ConcurrentHashMap<Long, Entry<T>>()
    private val worked = AtomicLong()

    /** How many times the answer had to be worked out rather than remembered. */
    val computations: Long get() = worked.get()

    fun of(id: Long, body: String): T {
        val known = entries[id]
        if (known != null && (known.body === body || known.body == body)) return known.value
        worked.incrementAndGet()
        val value = compute(body)
        if (entries.size >= limit) entries.clear()
        entries[id] = Entry(body, value)
        return value
    }

    fun of(message: com.zyke.message.domain.model.SmsMessage): T = of(message.id, message.body)

    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_LIMIT = 20_000

        /** Text longer than this is cut before a pattern with unbounded repeats reads it; no real message comes near it. */
        const val MAX_SCANNED_CHARS = 4_000

        fun bounded(body: String): String = if (body.length > MAX_SCANNED_CHARS) body.substring(0, MAX_SCANNED_CHARS) else body
    }
}
