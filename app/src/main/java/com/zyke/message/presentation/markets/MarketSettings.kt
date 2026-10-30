package com.zyke.message.presentation.markets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.StockDirectory
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FormField
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
            title = "Show market strip on Home",
            description = "A scrolling strip of live prices on Home; tap it for the full list. Prices come from Yahoo Finance over the internet, so this is off until you turn it on. Only the names of what you watch are sent, never anything from your messages.",
            checked = watch.enabled,
            onChange = { container.watchlist.save(watch.copy(enabled = it)) }
        )
        if (watch.enabled) {
            SwitchLine("US dollar in rupees", "Shows USD / INR", watch.showDollar, { container.watchlist.save(watch.copy(showDollar = it)) })
            SwitchLine("Gold", "Rupees for 10 grams, worked out from the international price", watch.showGold, { container.watchlist.save(watch.copy(showGold = it)) })

            WatchlistEditor()
        }
    }
}

/** The person's own list: what is in it with a Remove for each, and a field to add one, checked before it is kept. */
@Composable
fun WatchlistEditor() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val watch by container.watchlist.watch.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf("") }
    var checking by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
            Text(
                "Your list", Modifier, style = MaterialTheme.typography.labelLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.secondary
            )
            if (watch.symbols.isEmpty()) Caption("Nothing yet. Add a share or an index below.", Modifier.padding(top = 4.dp), maxLines = 2)
            watch.symbols.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RowTitle(item.label)
                        Caption(item.symbol)
                    }
                    AppButton("Remove", { container.watchlist.save(watch.copy(symbols = watch.symbols - item)) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
            }

            val offered = MarketSymbols.suggestions.filter { s -> watch.symbols.none { it.symbol == s.symbol } }
            if (offered.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    offered.forEach { s ->
                        AppButton("+ ${s.label}", { container.watchlist.save(watch.copy(symbols = watch.symbols + s)) }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
                    }
                }
            }

            FormField(
                label = "Add a share or index",
                value = typed,
                onChange = { typed = it; problem = null },
                modifier = Modifier.padding(top = 12.dp),
                placeholder = "Start typing a company, like Reliance or Tata",
                supporting = problem ?: "Pick from the names below, or type a ticker. A ticker alone means NSE; add .BO for BSE."
            )
            // As soon as there is something typed, the names that match it, each added with one tap.
            val matches = androidx.compose.runtime.remember(typed, watch.symbols) {
                StockDirectory.search(typed).filter { entry -> watch.symbols.none { it.symbol == entry.symbol } }
            }
            if (matches.isNotEmpty()) {
                Text(
                    if (typed.isBlank()) "Popular" else "Matches", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary
                )
                com.zyke.message.presentation.common.SuggestionRow(matches.map { "${it.name} \u00B7 ${it.symbol.removeSuffix(".NS")}" }) { picked ->
                    matches.firstOrNull { "${it.name} \u00B7 ${it.symbol.removeSuffix(".NS")}" == picked }?.let { entry ->
                        container.watchlist.save(watch.copy(symbols = watch.symbols + WatchSymbol(entry.symbol, entry.name)))
                        typed = ""
                        problem = null
                    }
                }
            }
            AppButton(
                if (checking) "Checking…" else "Add to the strip",
                onClick = {
                    val symbol = MarketSymbols.normalise(typed)
                    when {
                        symbol.isEmpty() -> problem = "Type a name first"
                        watch.symbols.any { it.symbol == symbol } -> problem = "Already in your list"
                        else -> {
                            checking = true
                            scope.launch {
                                val quote = container.markets.check(symbol)
                                checking = false
                                if (quote == null) {
                                    problem = "Couldn't find $symbol. Check the spelling, or that you are online."
                                } else {
                                    container.watchlist.save(watch.copy(symbols = watch.symbols + WatchSymbol(symbol, MarketSymbols.defaultLabel(symbol))))
                                    typed = ""
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
                style = ButtonStyle.OUTLINE,
                enabled = !checking && typed.isNotBlank()
            )
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
