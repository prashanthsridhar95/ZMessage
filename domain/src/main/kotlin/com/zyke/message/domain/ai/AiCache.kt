package com.zyke.message.domain.ai

/**
 * Answers worked out before, kept on the phone so the model is asked once per message. The key is a hash of the text and the
 * prompt version, never the text. Values are what the guard accepted (derived from the message), so the store is private,
 * left out of every backup and export, and cleared from Settings.
 */
interface AiCache {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clear()
    val size: Int
}

class InMemoryAiCache(private val max: Int = DEFAULT_MAX) : AiCache {
    private val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = synchronized(map) { map[key] }
    override fun put(key: String, value: String) = synchronized(map) {
        map.remove(key)
        map[key] = value
        while (map.size > max) map.remove(map.keys.first())
        Unit
    }
    override fun remove(key: String) { synchronized(map) { map.remove(key) } }
    override fun clear() = synchronized(map) { map.clear() }
    override val size: Int get() = synchronized(map) { map.size }

    companion object { const val DEFAULT_MAX = 400 }
}

object AiKeys {
    /** A stable key for one kind of answer about one text. */
    fun of(kind: String, text: String): String = kind + ":" + AiPrompts.VERSION + ":" + hash(text)

    /** 64-bit FNV-1a of the text, in hex. Not secret; only a name for the entry. */
    fun hash(text: String): String {
        var h = FNV_OFFSET
        for (c in text) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME
        }
        return java.lang.Long.toHexString(h)
    }

    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}
