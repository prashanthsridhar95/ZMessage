package com.zyke.message.presentation.markets

import android.content.Intent
import androidx.compose.runtime.mutableStateOf

/**
 * The hook by which the market-close notification opens the Market watch page. MainActivity sets [pending] from the incoming
 * intent; Home takes it, opens the page and clears it. Nothing is carried but the action.
 */
object MarketRequests {
    const val ACTION_OPEN = "com.zyke.message.action.OPEN_MARKET_WATCH"

    val pending = mutableStateOf(false)

    fun fromIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN) pending.value = true
    }
}
