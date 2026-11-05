package com.zyke.message.data.markets

import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.markets.ChartRange
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The second, and last, code in the app that talks to the internet for prices. It exists for the market strip, which is
 * off until the person switches it on. It sends only a ticker such as "RELIANCE.NS" or "USDINR=X" (the price of one), or
 * the few letters typed into the add-a-share box (the search for names), to Yahoo Finance's public endpoints. No message
 * text, numbers or amounts. A test keeps network calls out of every other file and pins the hosts.
 *
 * Yahoo's endpoints are unofficial and may change, rate-limit or block, so every call fails soft: a bad status, a
 * timeout or a broken reply gives null, never an exception. Logs hold status codes only, never what was typed.
 */
internal class QuoteFetcher {

    /** The chart reply for one ticker, or null if it could not be reached. A 404 for an unknown ticker is read like any reply. */
    fun fetch(symbol: String): String? =
        get("https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(symbol, "UTF-8")}?interval=1d&range=1d", "chart", acceptErrorBody = true)

    /**
     * The chart reply for one ticker over one period (the stock sheet), or null if it could not be reached. Sends only the
     * ticker and the period's name and step, such as "1mo" and "1d".
     */
    fun chart(symbol: String, range: ChartRange): String? =
        get(
            "https://query1.finance.yahoo.com/v8/finance/chart/${URLEncoder.encode(symbol, "UTF-8")}" +
                "?interval=${range.interval}&range=${range.range}&includePrePost=false",
            "history", acceptErrorBody = true
        )

    /** The search reply for what was typed, or null if it could not be reached or Yahoo refused (rate limit, block). */
    fun search(query: String): String? =
        get(
            "https://query2.finance.yahoo.com/v1/finance/search?q=${URLEncoder.encode(query.trim().take(MAX_QUERY), "UTF-8")}" +
                "&quotesCount=10&newsCount=0&listsCount=0&enableFuzzyQuery=false",
            "search", acceptErrorBody = false
        )

    private fun get(address: String, what: String, acceptErrorBody: Boolean): String? = try {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) DevLog.d("market") { "QuoteFetcher.$what: HTTP $code" }
            val stream = if (code in 200..299) connection.inputStream else if (acceptErrorBody) connection.errorStream else null
            stream?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        DevLog.d("market") { "QuoteFetcher.$what: ${e.javaClass.simpleName}" }
        null
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val MAX_BYTES = 256 * 1024
        const val MAX_QUERY = 40
    }
}
