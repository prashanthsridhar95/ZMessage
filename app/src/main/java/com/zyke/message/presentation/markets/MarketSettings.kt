package com.zyke.message.presentation.markets

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.markets.Market
import com.zyke.message.domain.markets.MarketCalendar
import com.zyke.message.domain.markets.MarketCloseSummary
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.WatchSettings
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import kotlinx.coroutines.launch

/**
 * The market strip's settings: one switch for the whole thing (off until chosen, since prices need the internet),
 * what to include, and the person's own tickers. A ticker is checked against the service when it is added, so a
 * typo is caught then and does not leave a line that never fills.
 */
@Composable
fun MarketSettingsSection() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val watch by container.watchlist.watch.collectAsStateWithLifecycle()

    SectionCard("Market watch") {
        SwitchLine(
            title = "Turn on market watch",
            description = "Turns the prices on. Home shows the strip only if you also choose it in Customize Home; the full list opens from here. Prices come from Yahoo Finance over the internet, so this is off until you turn it on. Only the tickers you watch, and the letters you type into the add box, are sent to Yahoo Finance, never anything from your messages.",
            checked = watch.enabled,
            onChange = { container.watchlist.save(watch.copy(enabled = it)) }
        )
        if (watch.enabled) {
            // The full price list used to open only from Home's strip, which is off in the calm layout: open it from here too.
            var listOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            AppButton("Open market watch", { listOpen = true }, style = ButtonStyle.OUTLINE, modifier = Modifier.padding(top = 8.dp))
            if (listOpen) MarketPage(onBack = { listOpen = false })
            SwitchLine("US dollar in rupees", "Shows USD / INR", watch.showDollar, { container.watchlist.save(watch.copy(showDollar = it)) })
            SwitchLine("Gold", "Rupees for 10 grams, worked out from the international price", watch.showGold, { container.watchlist.save(watch.copy(showGold = it)) })

            MarketCloseSection(watch) { change -> container.watchlist.save(change(container.watchlist.watch.value)) }

            WatchlistEditor()
        }
    }
}

/**
 * The person's own list: what is in it (name, symbol and exchange) with a Remove for each, and the add box, whose names
 * come from Yahoo Finance while typing. Adding or removing offers Undo.
 */
@Composable
fun WatchlistEditor() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val watch by container.watchlist.watch.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current

    // Works from the list as it is when Undo is tapped, not as it was when the change was made.
    fun offerUndo(message: String, undo: (WatchSettings) -> WatchSettings) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) container.watchlist.save(undo(container.watchlist.watch.value))
        }
    }

    Column {
        Text(
            "Your list (${watch.symbols.size} of ${MarketSymbols.MAX_WATCH})", Modifier, style = MaterialTheme.typography.labelLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.secondary
        )
        if (watch.symbols.isEmpty()) Caption("Nothing yet. Add a share or an index below.", Modifier.padding(top = 4.dp), maxLines = 2)
        watch.symbols.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RowTitle(item.label)
                    Caption(listOf(item.symbol, item.exchange).filter { it.isNotBlank() }.joinToString(" \u00B7 "))
                }
                AppButton("Remove", {
                    val index = watch.symbols.indexOf(item)
                    container.watchlist.save(watch.copy(symbols = watch.symbols - item))
                    offerUndo("Removed ${item.label}") { now ->
                        if (now.symbols.any { it.symbol == item.symbol }) now
                        else now.copy(symbols = now.symbols.toMutableList().apply { add(index.coerceIn(0, size), item) })
                    }
                }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
        if (watch.symbols.size >= MarketSymbols.MAX_WATCH) {
            Caption("Your list is full. Remove one to add another.", Modifier.padding(top = 4.dp), maxLines = 2)
        }

        val offered = MarketSymbols.suggestions.filter { s -> watch.symbols.none { it.symbol == s.symbol } }
        if (offered.isNotEmpty() && watch.symbols.size < MarketSymbols.MAX_WATCH) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offered.forEach { s ->
                    AppButton("+ ${s.label}", {
                        container.watchlist.save(watch.copy(symbols = watch.symbols + s))
                        offerUndo("Added ${s.label}") { now -> now.copy(symbols = now.symbols.filterNot { it.symbol == s.symbol }) }
                    }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
                }
            }
        }

        AddStockField(watch) { item ->
            val now = container.watchlist.watch.value
            if (now.symbols.none { it.symbol == item.symbol } && now.symbols.size < MarketSymbols.MAX_WATCH) {
                container.watchlist.save(now.copy(symbols = now.symbols + item))
                offerUndo("Added ${item.label}") { later -> later.copy(symbols = later.symbols.filterNot { it.symbol == item.symbol }) }
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            Caption(description, maxLines = 8)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * "Market close summary": one switch per market (India, United States) and how long after the close it arrives. Each is a quiet
 * notification with the market's indices and the day's move of the stocks you watch there, tapping it opens Market watch. Needs
 * permission to show notifications on newer Android, asked for only when a switch is turned on. Weekdays only; a day with no new
 * prices (an exchange holiday, which the app does not know in advance) sends nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketCloseSection(watch: WatchSettings, update: ((WatchSettings) -> WatchSettings) -> Unit) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf<Market?>(null) }
    fun set(market: Market, on: Boolean) = update { now ->
        when (market) {
            Market.INDIA -> now.copy(marketCloseSummary = on)
            Market.UNITED_STATES -> now.copy(marketCloseUs = on)
            else -> now
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val market = asking
        asking = null
        if (granted && market != null) set(market, true)
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            "Market close summary", style = MaterialTheme.typography.labelLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.secondary
        )
        Caption(
            "After a market's regular session ends, one quiet notification: how its indices closed and what each stock you watch there did that day, biggest movers first. Weekdays only, and nothing is sent on a day with no new prices, such as an exchange holiday. Off until you turn a market on.",
            maxLines = 8
        )
        MarketCloseSummary.MARKETS.forEach { market ->
            val checked = if (market == Market.INDIA) watch.marketCloseSummary else watch.marketCloseUs
            val stocks = MarketCloseSummary.stocksOf(market, watch.symbols).size
            val closes = market.closes?.let { "${it.hour % 12}:${it.minute.toString().padStart(2, '0')} ${if (it.hour < 12) "AM" else "PM"} ${MarketCalendar.zoneLabel(market)}" }.orEmpty()
            SwitchLine(
                title = "${market.flag} ${market.title}",
                description = "After the $closes close, ${market.indices.joinToString(", ") { it.second }}. " +
                    if (stocks == 0) "No stocks of yours are listed there yet; you get the indices only." else "Includes $stocks of your watched ${if (stocks == 1) "stock" else "stocks"}.",
                checked = checked,
                onChange = { wanted ->
                    val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    when {
                        !wanted -> set(market, false)
                        allowed -> set(market, true)
                        else -> {
                            asking = market
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            )
        }
        if (watch.marketCloseSummary || watch.marketCloseUs) {
            Text("Sent after the close", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarketCloseSummary.OFFSETS.forEach { minutes ->
                    FilterChip(
                        selected = watch.closeOffsetMinutes == minutes,
                        onClick = { update { it.copy(closeOffsetMinutes = minutes) } },
                        label = { Text(if (minutes == 60) "1 hour" else "$minutes min", maxLines = 1) }
                    )
                }
            }
            Caption("Android may move the time by a few minutes to save battery.", maxLines = 2)
        }
    }
}
