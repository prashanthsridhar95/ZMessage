package com.zyke.message.presentation.mvi

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Remembers the last answer of each derived part of a screen and gives it back while none of the part's inputs is a
 * different object (compared by identity, so a big list is never compared element by element). Safe to use from
 * several tasks at once as long as each part name is asked for by one task at a time, which is how a rebuild uses it.
 * Also notes how long each part took when it had to be worked out again, for the developer log.
 */
class PartMemo {
    private class Slot {
        @Volatile var keys: Array<out Any?>? = null
        @Volatile var value: Any? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val worked = AtomicInteger()
    private val nanos = ConcurrentHashMap<String, Long>()

    /** How many parts have been remembered so far. */
    val size: Int get() = slots.size

    /** How many parts were worked out again since [begin]. */
    val recomputed: Int get() = worked.get()

    /** Starts the books of a new rebuild. */
    fun begin() {
        worked.set(0)
        nanos.clear()
    }

    @Suppress("UNCHECKED_CAST")
    fun <V> of(name: String, vararg inputs: Any?, compute: () -> V): V {
        val slot = slots.getOrPut(name) { Slot() }
        val previous = slot.keys
        if (previous != null && previous.size == inputs.size && inputs.indices.all { previous[it] === inputs[it] }) return slot.value as V
        worked.incrementAndGet()
        val began = System.nanoTime()
        val result = compute()
        nanos[name] = System.nanoTime() - began
        slot.keys = inputs
        slot.value = result
        return result
    }

    /** "review 120 ms, accounts 40 ms, ...": the [count] parts that took longest since [begin]; names and times only. */
    fun slowest(count: Int): String =
        nanos.entries.sortedByDescending { it.value }.take(count).joinToString(", ") { "${it.key} ${it.value / 1_000_000} ms" }
}
