package com.zyke.message.presentation.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.domain.markets.WatchlistRepository
import com.zyke.message.domain.search.GlobalSearch
import com.zyke.message.domain.search.SearchHit
import com.zyke.message.domain.search.SearchKind
import com.zyke.message.domain.search.SearchResults
import com.zyke.message.domain.search.SearchSources
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.home.HomeContract
import com.zyke.message.presentation.home.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val DEBOUNCE_MS = 200L
private const val FOCUS_DELAY_MS = 300L
private const val EXPANDED_LIMIT = 50

/**
 * Connects the search page to what the app already holds: the messages and plans the Home state carries, and the
 * stocks being watched. Nothing is read or loaded again for a search.
 */
@Composable
fun SearchRoute(
    home: StateFlow<HomeContract.State>,
    watchlist: WatchlistRepository,
    onOpen: (SearchHit) -> Unit,
    onDismiss: () -> Unit
) {
    val state by home.collectAsStateWithLifecycle()
    val watch by watchlist.watch.collectAsStateWithLifecycle()
    val sources = remember(state.messages, state.goalList, state.reminderList, state.events, state.accounts, watch) {
        SearchSources(
            messages = state.messages,
            goals = state.goalList,
            reminders = state.reminderList,
            events = state.events,
            accounts = state.accounts.accounts,
            watched = watch.symbols
        )
    }
    SearchPage(sources, onOpen, onDismiss)
}

@Composable
fun SearchPage(sources: SearchSources, onOpen: (SearchHit) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults.EMPTY) }
    // Kinds the person asked to see more of.
    var expanded by remember { mutableStateOf(emptySet<SearchKind>()) }

    // Waits for a pause in typing, then searches off the main thread; a newer keystroke cancels the older search.
    LaunchedEffect(query, sources, expanded) {
        if (query.isBlank()) {
            results = SearchResults.EMPTY
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        val typed = query
        results = withContext(Dispatchers.Default) {
            val base = GlobalSearch.search(typed, sources)
            if (expanded.isEmpty()) base else {
                val more = GlobalSearch.search(typed, sources, EXPANDED_LIMIT)
                SearchResults(base.groups.map { g -> if (g.kind in expanded) more.groups.first { it.kind == g.kind } else g })
            }
        }
    }

    AppPage("Search", onDismiss) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            // The page is still sliding in; asking for focus at once can be dropped.
            delay(FOCUS_DELAY_MS)
            runCatching { focus.requestFocus() }
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = emptySet()
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            placeholder = { Text("Messages, amounts, goals, reminders, accounts") },
            trailingIcon = if (query.isNotEmpty()) {
                { TextButton(onClick = { query = "" }) { Text("Clear") } }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { })
        )
        when {
            query.isBlank() -> Text(
                "Type a name, an amount such as 540 or ₹1,200, or a category.",
                Modifier.padding(top = 16.dp, start = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            results.isEmpty -> EmptyState("No matches", "Nothing found for \"${query.trim()}\".", Modifier.padding(top = 16.dp))
            else -> Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                results.groups.forEach { group ->
                    SectionHeader(group.kind.label, group.total)
                    FintechCard {
                        group.hits.forEachIndexed { index, hit ->
                            if (index > 0) HorizontalDivider()
                            HitRow(hit, onOpen)
                        }
                        if (group.total > group.hits.size && group.kind !in expanded) {
                            TextButton(onClick = { expanded = expanded + group.kind }) { Text("Show all ${group.total}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, onOpen: (SearchHit) -> Unit) {
    // Stocks have nowhere to open to.
    val open = hit.kind != SearchKind.STOCK
    Row(
        Modifier.fillMaxWidth()
            .then(if (open) Modifier.clickable { onOpen(hit) } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hit.subtitle.isNotBlank()) {
                Text(
                    hit.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
