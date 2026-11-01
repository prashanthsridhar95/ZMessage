package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.WatchSettings
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.domain.markets.WatchlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The market strip's switch and the person's tickers, in private preferences. */
class WatchlistRepositoryImpl(context: Context) : WatchlistRepository {
    private val prefs = context.applicationContext.getSharedPreferences("market_watch", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    override val watch: StateFlow<WatchSettings> = state

    override fun save(settings: WatchSettings) {
        state.value = settings
        prefs.edit()
            .putBoolean(ENABLED, settings.enabled)
            .putBoolean(DOLLAR, settings.showDollar)
            .putBoolean(GOLD, settings.showGold)
            .putString(SYMBOLS, JSONArray().apply {
                settings.symbols.forEach {
                    put(JSONObject().put("s", it.symbol).put("l", it.label).apply { if (it.exchange.isNotBlank()) put("x", it.exchange) })
                }
            }.toString())
            .apply()
    }

    private fun read(): WatchSettings = runCatching {
        val array = JSONArray(prefs.getString(SYMBOLS, "[]"))
        WatchSettings(
            enabled = prefs.getBoolean(ENABLED, false),
            // Lists saved before names and exchanges were kept have only "s" and "l"; one damaged entry never loses the rest.
            symbols = (0 until array.length()).mapNotNull { index ->
                runCatching {
                    val item = array.getJSONObject(index)
                    val symbol = item.getString("s")
                    WatchSymbol(symbol, item.optString("l", "").ifBlank { MarketSymbols.defaultLabel(symbol) }, item.optString("x", ""))
                }.getOrNull()
            }.distinctBy { it.symbol },
            showDollar = prefs.getBoolean(DOLLAR, true),
            showGold = prefs.getBoolean(GOLD, true)
        )
    }.getOrDefault(WatchSettings())

    private companion object {
        const val ENABLED = "enabled"
        const val DOLLAR = "dollar"
        const val GOLD = "gold"
        const val SYMBOLS = "symbols"
    }
}
