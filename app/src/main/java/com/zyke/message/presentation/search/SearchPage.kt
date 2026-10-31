package com.zyke.message.presentation.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.domain.markets.WatchlistRepository
import com.zyke.message.domain.search.GlobalSearch
import com.zyke.message.domain.search.RecentSearchStore
import com.zyke.message.domain.search.RecentSearches
import com.zyke.message.domain.search.SearchHit
import com.zyke.message.domain.search.SearchKind
import com.zyke.message.domain.search.SearchPages
import com.zyke.message.domain.search.SearchQueryParser
import com.zyke.message.domain.search.SearchResults
import com.zyke.message.domain.search.SearchScope
import com.zyke.message.domain.search.SearchSources
import com.zyke.message.domain.search.SearchSuggestions
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.HIDDEN_AMOUNT
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.SearchField
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.HomeContract
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.theme.AppColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val DEBOUNCE_MS = 120L
private const val FOCUS_DELAY_MS = 300L
private const val FETCH_LIMIT = 50
private const val COLLAPSED_LIMIT = 4

/**
 * Connects the search page to what the app already holds: the messages and plans the Home state carries, and the
 * stocks being watched. Nothing is read or loaded again for a search.
 */
@Composable
fun SearchRoute(
    home: StateFlow<HomeContract.State>,
    watchlist: WatchlistRepository,
    recents: RecentSearchStore,
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
    SearchPage(sources, recents, onOpen, onDismiss)
}

/** One thing in the page's list. [type] lets the list reuse the layouts of rows of the same shape. */
private sealed interface Item {
    val key: String
    val type: Int

    data class Title(val id: String, val text: String, val count: Int? = null, val action: String? = null) : Item {
        override val key get() = "title-$id"
        override val type get() = 0
    }
    data class Recent(val query: String) : Item {
        override val key get() = "recent-$query"
        override val type get() = 1
    }
    data object Suggestions : Item {
        override val key get() = "suggestions"
        override val type get() = 2
    }
    data object Jump : Item {
        override val key get() = "jump"
        override val type get() = 3
    }
    data class Hit(val hit: SearchHit) : Item {
        override val key get() = "${hit.kind.name}-${hit.id}"
        override val type get() = if (hit.kind == SearchKind.MESSAGE || hit.kind == SearchKind.HAND_MADE) 4 else 5
    }
    data class More(val kind: SearchKind, val total: Int, val expanded: Boolean) : Item {
        override val key get() = "more-${kind.name}"
        override val type get() = 6
    }
    data class Empty(val query: String) : Item {
        override val key get() = "empty"
        override val type get() = 7
    }
    data class Tip(val text: String) : Item {
        override val key get() = "tip-$text"
        override val type get() = 8
    }
}

private val TIPS = listOf("swiggy last month", "food this month", ">5000", "salary under 2k", "5000", "settings")

@Composable
fun SearchPage(sources: SearchSources, recents: RecentSearchStore, onOpen: (SearchHit) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var scopeName by rememberSaveable { mutableStateOf(SearchScope.ALL.name) }
    val scope = remember(scopeName) { SearchScope.entries.firstOrNull { it.name == scopeName } ?: SearchScope.ALL }
    // Kinds the person asked to see all of.
    var expanded by remember { mutableStateOf(emptySet<SearchKind>()) }
    var recentList by remember { mutableStateOf(recents.load()) }
    val today = remember { LocalDate.now() }
    val zone = remember { ZoneId.systemDefault() }

    var results by remember { mutableStateOf(SearchResults.EMPTY) }
    // The query the results on show were found for, so "no matches" is never claimed for a query still being searched.
    var resultsFor by remember { mutableStateOf("") }

    // Waits for a pause in typing, then searches off the main thread; a newer keystroke cancels the older search.
    LaunchedEffect(query, scope, sources) {
        if (query.isBlank()) {
            results = SearchResults.EMPTY
            resultsFor = query
            return@LaunchedEffect
        }
        if (query != resultsFor) delay(DEBOUNCE_MS)
        val typed = query
        results = withContext(Dispatchers.Default) { GlobalSearch.search(typed, sources, FETCH_LIMIT, scope, today, zone) }
        resultsFor = typed
    }

    val parsed = remember(query) { SearchQueryParser.parse(query, today) }
    val words = remember(parsed) { parsed.text.split(' ').filter { it.isNotEmpty() } }

    fun commit(q: String) {
        val next = RecentSearches.add(recentList, q)
        if (next != recentList) {
            recentList = next
            recents.save(next)
        }
    }

    val items = remember(query, results, resultsFor, expanded, scope, recentList) {
        buildItems(query, results, resultsFor, expanded, scope, recentList)
    }

    AppPage("Search", onDismiss, floating = {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            SearchScreen(
                query = query,
                onQuery = {
                    query = it
                    expanded = emptySet()
                },
                scope = scope,
                onScope = { scopeName = it.name; expanded = emptySet() },
                parsed = parsed,
                words = words,
                items = items,
                today = today,
                zone = zone,
                onSearch = { commit(it) },
                onBack = onDismiss,
                onRunQuery = { q -> query = q; expanded = emptySet(); commit(q) },
                onRemoveRecent = { q -> recentList = RecentSearches.remove(recentList, q).also(recents::save) },
                onClearRecents = { recentList = emptyList(); recents.save(emptyList()) },
                onToggle = { kind -> expanded = if (kind in expanded) expanded - kind else expanded + kind },
                onHit = { hit ->
                    if (hit.kind == SearchKind.TAG && hit.messageId == null) {
                        // A tag has no page of its own: show the payments carrying it.
                        query = hit.title
                        scopeName = SearchScope.PAYMENTS.name
                        commit(hit.title)
                    } else {
                        commit(query)
                        onOpen(hit)
                    }
                },
                onJump = { key -> onOpen(SearchHit(SearchKind.PAGE, key, key, "")) }
            )
        }
    }) {}
}

/** The flat list the page shows: recents, suggestions and shortcuts when nothing is typed, else the grouped results. */
private fun buildItems(
    query: String,
    results: SearchResults,
    resultsFor: String,
    expanded: Set<SearchKind>,
    scope: SearchScope,
    recents: List<String>
): List<Item> = buildList {
    if (query.isBlank()) {
        if (recents.isNotEmpty()) {
            add(Item.Title("recent", "Recent", action = "Clear all"))
            recents.forEach { add(Item.Recent(it)) }
        }
        add(Item.Title("try", "Try"))
        add(Item.Suggestions)
        add(Item.Title("jump", "Jump to"))
        add(Item.Jump)
        return@buildList
    }
    if (results.isEmpty) {
        // Only once the search for what is typed has finished; until then the previous results (or nothing) stay.
        if (resultsFor == query) {
            add(Item.Empty(query.trim()))
            add(Item.Title("tips", "Search tips"))
            TIPS.forEach { add(Item.Tip(it)) }
        }
        return@buildList
    }
    results.groups.forEach { group ->
        add(Item.Title(group.kind.name, group.kind.label, group.total))
        val open = scope != SearchScope.ALL || group.kind in expanded
        val shown = if (open) group.hits else group.hits.take(COLLAPSED_LIMIT)
        shown.forEach { add(Item.Hit(it)) }
        if (scope == SearchScope.ALL && group.total > COLLAPSED_LIMIT) add(Item.More(group.kind, group.total, group.kind in expanded))
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    scope: SearchScope,
    onScope: (SearchScope) -> Unit,
    parsed: com.zyke.message.domain.search.ParsedSearch,
    words: List<String>,
    items: List<Item>,
    today: LocalDate,
    zone: ZoneId,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    onRunQuery: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    onToggle: (SearchKind) -> Unit,
    onHit: (SearchHit) -> Unit,
    onJump: (String) -> Unit
) {
    val highlight = SpanStyle(
        fontWeight = FontWeight.Bold,
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    )
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(R.drawable.baseline_arrow_back_24), contentDescription = "Back",
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
            }
            SearchField(
                query, onQuery, Modifier.weight(1f),
                placeholder = "Search payments, accounts, bills", onSearch = onSearch, focusDelayMs = FOCUS_DELAY_MS
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchScope.entries.forEach { s -> QuickChip(s.label, selected = s == scope, onClick = { onScope(s) }) }
        }
        if (parsed.hasFilters) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                parsed.chips.forEach { chip ->
                    QuickChip(chip.label, selected = false, removable = true, onClick = { onQuery(parsed.without(chip)) })
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
        ) {
            items(items, key = { it.key }, contentType = { it.type }) { item ->
                when (item) {
                    is Item.Title -> SectionTitle(item, onClearRecents)
                    is Item.Recent -> RecentRow(item.query, { onRunQuery(item.query) }, { onRemoveRecent(item.query) })
                    Item.Suggestions -> FlowChips(
                        SearchSuggestions.all.map { it.label to it.query }, onRunQuery
                    )
                    Item.Jump -> JumpTiles(onJump)
                    is Item.Hit -> HitRow(item.hit, words, highlight, today, zone) { onHit(item.hit) }
                    is Item.More -> MoreRow(item, onToggle)
                    is Item.Empty -> EmptyState(
                        "No matches", "Nothing found for “${item.query}”. Check the spelling, or try fewer words.",
                        Modifier.padding(top = 16.dp, bottom = 8.dp), icon = R.drawable.ic_search_24
                    )
                    is Item.Tip -> TipRow(item.text) { onRunQuery(item.text) }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SectionTitle(item: Item.Title, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            item.text.uppercase(),
            Modifier.heading(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
            color = mutedColor()
        )
        if (item.count != null) {
            Spacer(Modifier.width(8.dp))
            Text(item.count.toString(), style = MaterialTheme.typography.labelMedium, color = mutedColor())
        }
        Spacer(Modifier.weight(1f))
        if (item.action != null) {
            Box(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(item.action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A pill to choose or remove. The outer box is 48dp tall so it is easy to hit; the pill inside is slimmer. */
@Composable
private fun QuickChip(label: String, selected: Boolean, onClick: () -> Unit, removable: Boolean = false) {
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val ink = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(
        Modifier.heightIn(min = 48.dp)
            .semantics { if (removable) contentDescription = "$label filter, remove" }
            .clickable(role = if (removable) Role.Button else Role.Tab, onClickLabel = if (removable) "Remove filter" else null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.background(fill, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = ink, maxLines = 1)
            if (removable) {
                Spacer(Modifier.width(6.dp))
                Image(
                    painterResource(R.drawable.baseline_close_24), contentDescription = null, modifier = Modifier.size(14.dp),
                    colorFilter = ColorFilter.tint(ink)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, query) -> QuickChip(label, selected = false, onClick = { onPick(query) }) }
    }
}

@Composable
private fun RecentRow(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Search again", onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painterResource(R.drawable.ic_search_24), contentDescription = null, modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(mutedColor())
            )
            Spacer(Modifier.width(14.dp))
            Text(query, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "Remove from recent searches", onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(R.drawable.baseline_close_24), contentDescription = "Remove $query", modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(mutedColor())
            )
        }
    }
}

@Composable
private fun JumpTiles(onJump: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchPages.shortcuts.chunked(3).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTiles.forEach { page ->
                    Column(
                        Modifier.weight(1f).heightIn(min = 76.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button, onClickLabel = "Open ${page.title}") { onJump(page.key) }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Image(
                            painterResource(pageIcon(page.key)), contentDescription = null, modifier = Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            page.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1
                        )
                    }
                }
                // A short last row keeps its tiles the same size as the others.
                repeat(3 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TipRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Try this search", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Try", style = MaterialTheme.typography.bodySmall, color = mutedColor())
        Spacer(Modifier.width(10.dp))
        Text(
            text, Modifier.border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun MoreRow(item: Item.More, onToggle: (SearchKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = if (item.expanded) "Show fewer" else "Show all ${item.total}") { onToggle(item.kind) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (item.expanded) "Show less" else "See all ${item.total}",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun HitRow(hit: SearchHit, words: List<String>, highlight: SpanStyle, today: LocalDate, zone: ZoneId, onClick: () -> Unit) {
    val payment = hit.kind == SearchKind.MESSAGE || hit.kind == SearchKind.HAND_MADE
    // Stocks have nowhere to open to.
    val open = hit.kind != SearchKind.STOCK
    val title = remember(hit.title, words, highlight) { highlighted(hit.title, words, highlight) }
    val hidden = LocalPrivacyMode.current.hidden
    val dateText = remember(hit.date, today) {
        hit.date?.let { DateFormatters.dayHeader(LocalDate.ofInstant(Instant.ofEpochMilli(it), zone), today) }
    }
    val amountText = remember(hit.amount, hit.credit) {
        hit.amount?.let { (if (hit.credit) "+" else "−") + DisplayFormatters.rupees(it.replace(",", "")) }
    }
    val spoken = remember(hit, hidden, dateText, amountText) {
        listOfNotNull(
            hit.title, dateText, hit.category.takeIf { payment },
            if (payment) amountText?.let { if (hidden) "amount hidden" else if (hit.credit) "received $it" else "paid $it" } else null,
            hit.subtitle.takeIf { !payment && it.isNotBlank() }?.let { if (hidden) maskMoney(it) else it }
        ).joinToString(", ")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (open) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            if (payment) {
                MerchantChip(hit.logo ?: com.zyke.message.domain.model.MerchantLogo.PERSON, size = 44.dp, imageKey = hit.imageKey, name = hit.title)
            } else {
                KindTile(hit)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (payment) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dateText != null) Text(dateText, style = MaterialTheme.typography.bodySmall, color = mutedColor(), maxLines = 1)
                        val category = hit.category
                        if (!category.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                category,
                                Modifier.border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp),
                                style = MaterialTheme.typography.labelSmall, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else if (hit.subtitle.isNotBlank()) {
                    Text(
                        if (hidden) maskMoney(hit.subtitle) else hit.subtitle,
                        style = MaterialTheme.typography.bodySmall, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (payment && amountText != null) {
                Spacer(Modifier.width(12.dp))
                PrivateAmount(
                    amountText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hit.credit) AppColors.success else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            } else if (open && !payment) {
                Image(
                    painterResource(R.drawable.baseline_chevron_right_24), contentDescription = null, modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(mutedColor())
                )
            }
        }
    }
}

@Composable
private fun KindTile(hit: SearchHit) {
    Box(
        Modifier.size(44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        val icon = when (hit.kind) {
            SearchKind.ACCOUNT -> R.drawable.ic_accounts_24
            SearchKind.REMINDER -> R.drawable.baseline_receipt_24
            SearchKind.GOAL -> R.drawable.ic_goal_24
            SearchKind.EVENT -> R.drawable.baseline_event_24
            SearchKind.STOCK -> R.drawable.ic_trending_24
            SearchKind.PAGE -> pageIcon(hit.id)
            else -> null
        }
        if (icon != null) {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
        } else {
            Text("#", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun pageIcon(key: String): Int = when (key) {
    SearchPages.ACCOUNTS -> R.drawable.ic_accounts_24
    SearchPages.BILLS, SearchPages.REMINDERS, SearchPages.SUBSCRIPTIONS -> R.drawable.baseline_receipt_24
    SearchPages.INSIGHTS -> R.drawable.ic_insights_24
    SearchPages.MESSAGES -> R.drawable.baseline_message_24
    SearchPages.SETTINGS -> R.drawable.baseline_settings_24
    SearchPages.CALENDAR -> R.drawable.baseline_event_24
    SearchPages.GOALS -> R.drawable.ic_goal_24
    else -> R.drawable.ic_search_24
}

/** Hides every rupee figure in a line of text, for privacy mode. */
private fun maskMoney(text: String): String =
    text.split(' ').joinToString(" ") { if (it.startsWith("₹")) HIDDEN_AMOUNT else it }
