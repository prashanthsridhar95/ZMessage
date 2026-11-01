package com.zyke.message.presentation.markets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.markets.SearchOutcome
import com.zyke.message.domain.markets.MarketNames
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.StockDirectory
import com.zyke.message.domain.markets.StockSuggestion
import com.zyke.message.domain.markets.SuggestionMerge
import com.zyke.message.domain.markets.WatchSettings
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.messages.components.Caption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEBOUNCE_MS = 250L
private const val MIN_CHARS = 2

private enum class SearchStatus { IDLE, SEARCHING, DONE, FAILED }

/**
 * The add-a-share box: as the person types, names from Yahoo Finance (after a short pause, two letters at least, and
 * only while the market strip is switched on) joined with a built-in list that answers at once and still works offline.
 * Tapping a name adds it. Any ticker typed by hand can also be added, once Yahoo is shown to have a price for it.
 */
@Composable
internal fun AddStockField(watch: WatchSettings, onAdd: (WatchSymbol) -> Unit) {
    val ticker = (LocalContext.current.applicationContext as ZykeMessagesApplication).container.markets
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<List<StockSuggestion>>(emptyList()) }
    var status by remember { mutableStateOf(SearchStatus.IDLE) }
    val query = typed.trim()
    val online = watch.enabled
    val full = watch.symbols.size >= MarketSymbols.MAX_WATCH

    // A newer keystroke cancels this block, so a slow answer for old text is never shown; the work itself runs on IO.
    LaunchedEffect(query, online) {
        remote = emptyList()
        if (!online || query.length < MIN_CHARS) {
            status = SearchStatus.IDLE
            return@LaunchedEffect
        }
        status = SearchStatus.SEARCHING
        delay(DEBOUNCE_MS)
        when (val outcome = ticker.search(query)) {
            is SearchOutcome.Found -> { remote = outcome.suggestions; status = SearchStatus.DONE }
            SearchOutcome.Unreachable -> status = SearchStatus.FAILED
        }
    }

    val local = remember(query) { StockDirectory.search(query).map { SuggestionMerge.fromLocal(it) } }
    val watchedSymbols = remember(watch.symbols) { watch.symbols.map { it.symbol }.toSet() }
    val shown = remember(remote, local, watchedSymbols) { SuggestionMerge.merge(remote, local, watchedSymbols) }

    fun add(item: WatchSymbol) {
        if (full) {
            problem = "Your list is full (${MarketSymbols.MAX_WATCH}). Remove one to add another."
        } else {
            onAdd(item)
            typed = ""
            problem = null
        }
    }

    Column {
        FormField(
            label = "Add a share, index, currency or crypto",
            value = typed,
            onChange = { typed = it; problem = null },
            modifier = Modifier.padding(top = 12.dp),
            placeholder = "Start typing a name or symbol, like Reliance, Apple or 7203.T",
            error = problem,
            supporting = null
        )
        Caption(
            if (online) "Suggestions come from Yahoo Finance; what you type is sent to it."
            else "Suggestions come from a built-in list. Turn on the market strip to search Yahoo Finance; nothing you type is sent.",
            Modifier.padding(top = 4.dp), maxLines = 3
        )

        if (shown.isNotEmpty()) {
            Text(
                if (query.isBlank()) "Popular" else "Matches", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary
            )
            Column(Modifier.fillMaxWidth()) {
                shown.forEach { item -> SuggestionLine(item) { add(item.toWatchSymbol()) } }
            }
        }
        when (status) {
            SearchStatus.SEARCHING -> Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Searching…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            SearchStatus.DONE -> if (shown.isEmpty()) StatusLine("No matches")
            SearchStatus.FAILED -> StatusLine("Couldn't reach the service — you can still type a symbol and add it")
            SearchStatus.IDLE -> if (query.length >= MIN_CHARS && shown.isEmpty()) StatusLine("No matches in the built-in list")
        }

        AppButton(
            if (checking) "Checking…" else "Add “${query.uppercase().take(16)}” as typed",
            onClick = {
                val candidates = MarketSymbols.candidates(query)
                when {
                    candidates.isEmpty() -> problem = "That doesn't look like a symbol. Use letters, digits and . - = ^ only."
                    candidates.any { it in watchedSymbols } -> problem = "Already in your list"
                    full -> problem = "Your list is full (${MarketSymbols.MAX_WATCH}). Remove one to add another."
                    else -> {
                        checking = true
                        scope.launch {
                            val quote = ticker.checkTyped(query)
                            checking = false
                            if (quote == null) {
                                problem = "Yahoo has no price for ${query.uppercase().take(24)}. Check the symbol, or that you are online."
                            } else {
                                val known = shown.firstOrNull { it.symbol == quote.symbol }?.toWatchSymbol()
                                add(known ?: WatchSymbol(quote.symbol, MarketSymbols.defaultLabel(quote.symbol), MarketNames.exchange(quote.exchange, null)))
                            }
                        }
                    }
                }
            },
            modifier = Modifier.padding(top = 8.dp),
            style = ButtonStyle.OUTLINE,
            enabled = !checking && query.isNotBlank()
        )
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text, Modifier.heightIn(min = 48.dp).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

/** One suggestion: ticker in bold, the company name beneath, and chips for the exchange and the kind. */
@Composable
private fun SuggestionLine(item: StockSuggestion, onPick: () -> Unit) {
    val spoken = buildString {
        append("Add ").append(item.name).append(", ").append(item.symbol)
        if (item.exchange.isNotBlank()) append(", ").append(item.exchange)
        append(", ").append(item.type.label)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Add ${item.name} to your list", onClick = onPick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.exchange.isNotBlank()) Chip(item.exchange)
        Chip(item.type.label)
    }
}

@Composable
private fun Chip(label: String) {
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1
    )
}
