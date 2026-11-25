package com.zyke.message.domain.places

import com.zyke.message.domain.model.SmsMessage

/**
 * The identity a stored place is filed under. The live receiver does not know the provider's row id, and the inbox
 * read later gives the same text a different id, so the key is made from what both sides know: the sender, the words
 * of the text (spacing folded) and the minute it arrived. A 64-bit FNV-1a hash of those, in hex: the key reveals nothing.
 *
 * The receiver stamps the text with the phone's clock and the provider with the network's, which can fall on either
 * side of a minute change, so [nearby] also names the minute before and after; a lookup tries all three.
 */
object PlaceKey {
    private const val MINUTE_MS = 60_000L
    private val SPACES = Regex("\\s+")

    fun of(message: SmsMessage): String = of(message.sender, message.body, message.date)

    fun of(sender: String, body: String, dateMillis: Long): String = hash(sender, body, Math.floorDiv(dateMillis, MINUTE_MS))

    /** The key and its two neighbouring minutes, the exact one first. */
    fun nearby(message: SmsMessage): List<String> {
        val minute = Math.floorDiv(message.date, MINUTE_MS)
        return listOf(minute, minute - 1, minute + 1).map { hash(message.sender, message.body, it) }
    }

    private fun hash(sender: String, body: String, minute: Long): String {
        val text = sender.trim().lowercase() + "\u0001" + body.trim().replace(SPACES, " ") + "\u0001" + minute
        var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }
}
