package com.zyke.message.domain.train

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The pure parts of talking to the live train status provider (Live Train Status, Indian Rail API, indianrailapi.com): what a valid
 * personal key looks like, the date format its address takes, and removing a key from any text before it is shown or shared.
 *
 * The provider's address, reply format and response codes come from the documentation page its owner published (the example there is
 * dated 2018); none of it has been checked against the live service from the build environment.
 */
object TrainApi {
    const val HOST = "indianrailapi.com"

    /** The longest key the field accepts. */
    const val KEY_MAX = 100

    /** What stands in for the key wherever an address is shown or shared. */
    const val KEY_PLACEHOLDER = "<key>"

    /** Characters that would change the meaning of an address if they sat inside the key. */
    private const val URL_RESERVED = "/?#%\\\"'<>"

    /** The one line shown under the key field when [raw] cannot be a key, or null when it can. */
    fun keyProblem(raw: String): String? {
        val key = raw.trim()
        return when {
            key.isEmpty() -> "Paste the key here"
            key.any { it.isWhitespace() } -> "A key has no spaces"
            key.length > KEY_MAX -> "That is too long for a key"
            key.any { it.code !in 33..126 || it in URL_RESERVED } -> "That has a character a key does not have"
            else -> null
        }
    }

    fun isValidKey(raw: String): Boolean = keyProblem(raw) == null

    /** The key as stored: the text the person pasted, trimmed. */
    fun cleanKey(raw: String): String = raw.trim()

    /** The journey date as the address takes it: yyyymmdd. */
    fun dateParam(date: LocalDate): String = date.format(DATE)

    /** The address of one request with the key left out, for the developer's shared text. */
    fun redactedRequest(number: String, date: LocalDate): String =
        "https://$HOST/api/v2/livetrainstatus/apikey/$KEY_PLACEHOLDER/trainnumber/${number.filter { it.isDigit() }.take(5)}/date/${dateParam(date)}/"

    /**
     * [text] with the key taken out: every occurrence of [key] (also as typed into an address), and whatever follows `apikey/` or
     * `apikey=` in any address, becomes [KEY_PLACEHOLDER]. Safe to call with no key: the address patterns still apply.
     */
    fun redact(text: String, key: String? = null): String {
        var out = text
        val k = key?.trim().orEmpty()
        if (k.length >= MIN_KEY_TO_REPLACE) out = out.replace(k, KEY_PLACEHOLDER)
        return out.replace(PATH_KEY, "$1$KEY_PLACEHOLDER").replace(QUERY_KEY, "$1$KEY_PLACEHOLDER")
    }

    private const val MIN_KEY_TO_REPLACE = 3
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val PATH_KEY = Regex("(?i)(apikey/)[^/\\s\"'?&<>]+")
    private val QUERY_KEY = Regex("(?i)(api[_-]?key=)[^&\\s\"'<>]+")
}
