package com.zyke.message.data.markets

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The second, and last, code in the app that talks to the internet. It exists for the market strip, which is off until
 * the person switches it on, and the only thing it sends is a ticker such as "RELIANCE.NS" or "USDINR=X", the very
 * thing the person typed into their watch list. No message text, numbers or amounts. A test keeps network calls out of
 * every other file.
 */
internal class QuoteFetcher {

    /** The service's raw reply for one ticker, or null if it could not be reached. */
    fun fetch(symbol: String): String? = runCatching {
        val address = "https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(symbol, "UTF-8")}?interval=1d&range=1d"
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ZykeMessage")
            setRequestProperty("Accept", "application/json")
        }
        try {
            // An unknown ticker answers 404 with a JSON body; both that and a good reply are read, anything else is a failure.
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val MAX_BYTES = 256 * 1024
    }
}
