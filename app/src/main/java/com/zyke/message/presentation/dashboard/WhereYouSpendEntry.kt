package com.zyke.message.presentation.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.dashboard.components.Chevron
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.places.SpendMapRequests

/**
 * The Insights entry to the Spending map. It says what the page holds (or how places get collected) in one line and opens the map;
 * it reads only the count of saved places, never a position.
 */
@Composable
internal fun WhereYouSpendEntry() {
    val container = (LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val state by container.spendPlaces.state.collectAsState()
    FintechCard(onClick = { SpendMapRequests.show() }) {
        CardHeader("Where you spend", trailing = { Chevron() })
        Caption(
            when {
                state.places.isNotEmpty() -> "${DisplayFormatters.count(state.places.size, "place", "places")} on a map, as heat and price tags"
                state.settings.enabled -> "Places appear here as purchase texts arrive"
                else -> "See your spending on a map. Off until you turn it on"
            },
            maxLines = 2
        )
    }
}
