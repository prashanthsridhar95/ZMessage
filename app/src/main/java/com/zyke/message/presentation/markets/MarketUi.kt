package com.zyke.message.presentation.markets

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.markets.MarketState
import com.zyke.message.data.markets.MarketTicker
import com.zyke.message.domain.markets.CurrencySymbols
import com.zyke.message.domain.markets.MarketCalendar
import com.zyke.message.domain.markets.MarketGrouping
import com.zyke.message.domain.markets.MarketGroup
import com.zyke.message.domain.markets.MarketKind
import com.zyke.message.domain.markets.MarketRow
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.rememberReduceMotion
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.Danger
import com.zyke.message.presentation.home.Good
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.home.homeStacked
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.tonalFill
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REFRESH_MS = 20_000L

internal fun Context.marketTicker(): MarketTicker = (applicationContext as ZykeMessagesApplication).container.markets

/** Whether the strip is switched on; Home shows nothing at all when it is not. */
@Composable
fun marketsEnabled(): Boolean {
    val ticker = LocalContext.current.marketTicker()
    val settings by ticker.settings.collectAsStateWithLifecycle()
    return settings.enabled
}

/** Keeps the prices fresh while the screen that shows them is in front, and not a moment longer. */
@Composable
fun MarketPolling() {
    val ticker = LocalContext.current.marketTicker()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settings by ticker.settings.collectAsStateWithLifecycle()
    LaunchedEffect(settings) {
        ticker.reshow()
        if (!settings.enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                ticker.refresh()
                delay(REFRESH_MS)
            }
        }
    }
}

internal fun priceText(row: MarketRow): String {
    val code = row.currency
    return when {
        // The dollar and gold rows are rupees worked out here; the gold one is a whole number.
        code == "INR" && row.kind == MarketKind.COMMODITY -> "\u20B9" + indian(row.price, 0)
        code == "INR" -> "\u20B9" + indian(row.price, 2)
        code.isEmpty() && row.kind == MarketKind.CURRENCY -> FormatCache.number(Locale.US, 4, 4).format(row.price)
        code.isEmpty() -> indian(row.price, 2)
        else -> {
            // Each quote in its own currency, as the service reported it (never assumed to be rupees).
            val digits = CurrencySymbols.digits(code, row.price)
            CurrencySymbols.prefix(code) + FormatCache.number(Locale.US, digits, digits).format(row.price) + CurrencySymbols.suffix(code)
        }
    }
}

private fun changeAmount(row: MarketRow, value: BigDecimal): String =
    if (row.currency.isEmpty() || row.currency == "INR") indian(value, 2)
    else CurrencySymbols.digits(row.currency, row.price).let { FormatCache.number(Locale.US, it, it).format(value) }

private fun indian(value: BigDecimal, digits: Int): String =
    FormatCache.number(INDIAN, digits, digits).format(value)

private val INDIAN: Locale = Locale.forLanguageTag("en-IN")

private fun spoken(row: MarketRow): String {
    val move = when {
        row.isUp -> "up ${row.changePercent?.abs()?.toPlainString()} percent"
        row.isDown -> "down ${row.changePercent?.abs()?.toPlainString()} percent"
        else -> "unchanged"
    }
    return "${row.label} ${priceText(row)}, $move"
}


@Composable
private fun moveColor(row: MarketRow): Color = when {
    row.isUp -> Good
    row.isDown -> Danger
    else -> mutedColor()
}

private const val TABULAR = "tnum"
private val ROW_HEIGHT = 56.dp
private val STRIP_HEIGHT = 40.dp
private const val SCROLL_DP_PER_SECOND = 36f

@Composable
private fun WatchDivider() = HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

/** "+416.15" with a real minus sign, or null when the service gave no previous close. */
private fun signedChange(row: MarketRow): String? =
    row.change?.let { (if (it.signum() >= 0) "+" else "−") + changeAmount(row, it.abs()) }

private fun signedPercent(row: MarketRow, brackets: Boolean = true): String? =
    row.changePercent?.let {
        val text = (if (it.signum() >= 0) "+" else "−") + it.abs().toPlainString() + "%"
        if (brackets) "($text)" else text
    }

/** "+2.70 (+1.04%)": the signs say it without the colour. Blank when nothing is known. */
private fun moveLine(row: MarketRow): String = listOfNotNull(signedChange(row), signedPercent(row)).joinToString(" ")

/** The small grey line under a name: its exchange, or for the dollar and gold what they measure. Nothing when unknown. */
private fun subLine(row: MarketRow): String =
    row.exchange.ifBlank { if (row.kind == MarketKind.STOCK || row.kind == MarketKind.INDEX) "" else row.detail }

private fun clock(pattern: String, millis: Long): String =
    FormatCache.pattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** The quiet state line: when the prices last arrived, and that they are the last ones when the service cannot be reached. */
private fun statusText(state: MarketState, pattern: String): String {
    val at = state.updatedAt?.let { clock(pattern, it) }
    return when {
        state.offline && at != null -> "Offline · updated $at"
        state.offline -> "Offline"
        at != null -> "Updated $at"
        state.loading -> "Updating…"
        else -> ""
    }
}

/** "▲ 2.70 (1.04%)": the arrow carries the direction without the colour. Blank when the service gave no previous close. */
private fun tickerMove(row: MarketRow): String {
    val arrow = when {
        row.isUp -> "▲ "
        row.isDown -> "▼ "
        else -> ""
    }
    val amount = row.change?.let { changeAmount(row, it.abs()) }
    val percent = row.changePercent?.let { "(" + it.abs().toPlainString() + "%)" }
    val text = listOfNotNull(amount, percent).joinToString(" ")
    return if (text.isEmpty()) "" else arrow + text
}

/** One ticker item: NAME, price, arrow and move. The move is the only coloured part. */
@Composable
private fun TickerItem(row: MarketRow) {
    Row(Modifier.padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(
            row.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            priceText(row), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
        )
        tickerMove(row).takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.width(6.dp))
            Text(
                it, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), fontWeight = FontWeight.Bold,
                color = moveColor(row), maxLines = 1, softWrap = false
            )
        }
    }
}

/** Lays out the items in a row and places only the ones that fit whole: the still strip never shows a half-cut name. */
@Composable
private fun WholeItemsRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        var used = 0
        val shown = placeables.takeWhile { p -> (used + p.width <= constraints.maxWidth).also { fits -> if (fits) used += p.width } }
        val height = (shown.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth.coerceAtLeast(0), height) {
            var x = 0
            shown.forEach { p ->
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width
            }
        }
    }
}

/** An index in the header: NAME muted, its level, and today's move beneath (only the move takes green or red). Opens its sheet. */
@Composable
private fun IndexCell(row: MarketRow, onOpen: (MarketRow) -> Unit, modifier: Modifier) {
    val open = row.symbol.isNotEmpty()
    Column(
        modifier
            .heightIn(min = ROW_HEIGHT)
            .clearAndSetSemantics {
                contentDescription = spoken(row)
                if (open) {
                    role = Role.Button
                    onClick(label = "Open chart") { onOpen(row); true }
                }
            }
            .then(if (open) Modifier.clickable(role = Role.Button, onClickLabel = "Open chart") { onOpen(row) } else Modifier)
            .padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            row.label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, color = mutedColor(),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            priceText(row), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
        )
        moveLine(row).takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = moveColor(row), maxLines = 1, softWrap = false)
        }
    }
}

/** The two indices on one fixed line (stacked at 320 dp and large text). No accordion: a tap opens that index's sheet. */
@Composable
private fun IndexLine(indices: List<MarketRow>, onOpen: (MarketRow) -> Unit) {
    if (homeStacked()) {
        Column(Modifier.fillMaxWidth()) {
            indices.forEachIndexed { i, row ->
                IndexCell(row, onOpen, Modifier.fillMaxWidth())
                if (i < indices.lastIndex) WatchDivider()
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            indices.forEach { row -> IndexCell(row, onOpen, Modifier.weight(1f)) }
        }
    }
}

/** Kite's "Search & add" bar: opens the add box. The count is how many of the allowed names are on the list. */
@Composable
private fun SearchAddBar(count: Int, onActivate: () -> Unit) {
    val summary = "Search and add. $count of ${MarketSymbols.MAX_WATCH} names on your list"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(AppShapes.control)
            .background(tonalFill(Tonal.Quiet))
            .clearAndSetSemantics {
                contentDescription = summary
                role = Role.Button
                onClick(label = "Edit the watch list") { onActivate(); true }
            }
            .clickable(role = Role.Button, onClickLabel = "Edit the watch list", onClick = onActivate)
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(R.drawable.ic_search_24), contentDescription = null, Modifier.size(20.dp), tint = mutedColor())
        Text("Search & add", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "$count/${MarketSymbols.MAX_WATCH}", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR),
            color = mutedColor(), maxLines = 1, softWrap = false
        )
    }
}

/**
 * One line of the list, Kite-style: name and exchange on the left, price (green or red) with its move in muted grey beneath on the
 * right, no card, one 56 dp height. A tap opens the sheet; a long press (page only) opens the watch list editor.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchRow(row: MarketRow, onOpen: (() -> Unit)?, onEdit: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clearAndSetSemantics {
                contentDescription = spoken(row)
                if (onOpen != null) {
                    role = Role.Button
                    onClick(label = "Open chart") { onOpen(); true }
                }
                if (onEdit != null) {
                    customActions = listOf(CustomAccessibilityAction("Edit the watch list") { onEdit(); true })
                }
            }
            .then(
                if (onOpen != null) Modifier.combinedClickable(
                    role = Role.Button, onClickLabel = "Open chart", onClick = onOpen,
                    onLongClickLabel = if (onEdit != null) "Edit the watch list" else null, onLongClick = onEdit
                ) else Modifier
            )
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subLine(row).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                priceText(row), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = TABULAR, fontWeight = FontWeight.Medium),
                color = moveColor(row), maxLines = 1, softWrap = false
            )
            moveLine(row).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = mutedColor(), maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The line above a market's rows: its flag (two regional-indicator letters, drawn by the phone's emoji font; a phone without
 * one shows the two letters, which still read as the country), the country name, whether its regular session is open (a hint: holidays
 * are not modelled) and the market's index with today's move when it is on the list. Read as one heading by a screen reader.
 */
@Composable
private fun MarketHeader(group: MarketGroup, now: Instant) {
    val market = group.market
    val hint = MarketCalendar.hint(market, now)
    val index = group.index?.takeIf { it.changePercent != null }
    val indexText = index?.let { "${it.label} ${signedPercent(it, brackets = false)}" }
    val indexColor = if (index != null) moveColor(index) else mutedColor()
    val spokenIndex = index?.let { ". ${spoken(it)}" }.orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .clip(AppShapes.control)
            .background(tonalFill(Tonal.Quiet))
            .heightIn(min = 48.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .clearAndSetSemantics {
                heading()
                contentDescription = market.title + (hint?.let { ". " + it.replace("\u00B7", ",") } ?: "") + spokenIndex
            },
        verticalArrangement = Arrangement.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (market.flag.isNotEmpty()) Text(market.flag, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
            Text(
                market.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            // Beside the name when there is room; beneath it at 320 dp and large text.
            if (indexText != null && !homeStacked()) {
                Text(
                    indexText, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = indexColor,
                    maxLines = 1, softWrap = false
                )
            }
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = mutedColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (indexText != null && homeStacked()) {
            Text(indexText, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = indexColor, maxLines = 2)
        }
    }
}

/** Placeholder rows while the first prices come: the same 56 dp, no motion. */
@Composable
private fun WatchSkeleton(count: Int = 3) {
    val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Loading prices" }) {
        repeat(count) { i ->
            Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.width(88.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(bar))
                    Box(Modifier.width(36.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.width(64.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(bar))
                    Box(Modifier.width(96.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                }
            }
            if (i < count - 1) WatchDivider()
        }
    }
}

/** What stands in for the list when there are no prices: offline with a way to retry, or nothing watched yet. */
@Composable
private fun WatchEmpty(offline: Boolean, loading: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            if (offline) "Can't reach the price service" else "Nothing to watch yet",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface
        )
        Caption(if (offline) "Prices show here once you are back online." else "Add names in Settings › Market watch.", maxLines = 2)
        if (offline) {
            Spacer(Modifier.height(Spacing.sm))
            AppButton("Try again", onRetry, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, enabled = !loading)
        }
    }
}

/**
 * Home's first item: a slim single-line ticker. "NAME price ▲ move (pct)" items scroll past; a tap opens the Market watch page.
 * The band says what it holds to a screen reader in one sentence, and its text carries an arrow, not only a colour. Under
 * reduced motion or Lite nothing scrolls: the first items that fit whole are shown. Public quotes, so Hide amounts does not apply.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarketStrip(onOpen: () -> Unit) {
    val ticker = LocalContext.current.marketTicker()
    val state by ticker.state.collectAsStateWithLifecycle()
    val still = rememberReduceMotion()
    val rows = state.rows
    Row(
        Modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .clip(AppShapes.control)
            .background(tonalFill(Tonal.Quiet))
            .clickable(role = Role.Button, onClickLabel = "Open market watch", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (rows.isEmpty()) {
            Caption(
                if (state.offline) "Can't reach the price service · tap for details" else "Loading prices…",
                Modifier.padding(horizontal = Spacing.lg)
            )
        } else {
            val spokenAll = "Market watch. " + rows.joinToString(". ") { spoken(it) }
            if (still) {
                WholeItemsRow(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spokenAll }) { rows.forEach { TickerItem(it) } }
            } else {
                // One row of everything, drawn once, with the system's own marquee moving it: no frame loop of ours.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { contentDescription = spokenAll }
                        .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, spacing = MarqueeSpacing(24.dp), velocity = SCROLL_DP_PER_SECOND.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { rows.forEach { TickerItem(it) } }
            }
        }
    }
}

/**
 * The watchlist, like Zerodha Kite: the two indices on a fixed line (a tap opens that index's sheet), the "Search & add" bar with
 * its count, then dense 56 dp rows: name and exchange left, price and move right, a hairline between each. A row opens its sheet;
 * long press, or the bar, opens the editor.
 */
@Composable
fun MarketPage(onBack: () -> Unit) {
    val ticker = LocalContext.current.marketTicker()
    val state by ticker.state.collectAsStateWithLifecycle()
    val watch by ticker.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var editing by rememberSaveable { mutableStateOf(false) }
    // The ticker whose sheet is open. Rows are only tappable on this page, which is only reachable with the market watch on.
    var chartSymbol by rememberSaveable { mutableStateOf<String?>(null) }

    AppPage("Market watch", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val indices = state.rows.filter { it.kind == MarketKind.INDEX }.take(2)
            if (indices.isNotEmpty()) {
                IndexLine(indices) { chartSymbol = it.symbol }
            }
            SearchAddBar(watch.symbols.size) { editing = true }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption(statusText(state, "h:mm:ss a"), Modifier.weight(1f).padding(end = Spacing.md), maxLines = 2)
                AppButton("Refresh", { scope.launch { ticker.refresh() } }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, enabled = !state.loading)
            }
            if (state.offline && state.rows.isNotEmpty()) StatusPill("Offline · showing the last prices", Tone.WARN)

            // One dense list in the order the person set: no card per row, a hairline between them. With "Group by market" the
            // same rows sit under a header per market (the chip shows only when the list spans more than one).
            val list = state.rows.filter { it !in indices }
            val offered = MarketGrouping.offered(state.rows)
            val grouped = MarketGrouping.shown(watch.groupByMarket, state.rows)
            if (offered) {
                FilterChip(
                    selected = grouped,
                    onClick = { ticker.setGroupByMarket(!grouped) },
                    label = { Text("Group by market", maxLines = 1) }
                )
            }
            Column(Modifier.fillMaxWidth()) {
                if (state.rows.isEmpty()) {
                    if (state.offline || state.updatedAt != null) WatchEmpty(state.offline, state.loading) { scope.launch { ticker.refresh() } }
                    else WatchSkeleton(6)
                }
                if (grouped) {
                    val now = remember(state.updatedAt) { Instant.now() }
                    MarketGrouping.group(list, indexSource = state.rows).forEach { group ->
                        MarketHeader(group, now)
                        group.rows.forEach { row ->
                            WatchRow(row, onOpen = if (row.symbol.isNotEmpty()) ({ chartSymbol = row.symbol }) else null, onEdit = { editing = true })
                            WatchDivider()
                        }
                    }
                } else {
                    WatchDivider()
                    list.forEach { row ->
                        WatchRow(row, onOpen = if (row.symbol.isNotEmpty()) ({ chartSymbol = row.symbol }) else null, onEdit = { editing = true })
                        WatchDivider()
                    }
                }
            }
            Caption("Refreshes every 20 seconds while this is open. Prices can be delayed by up to 15 minutes.", maxLines = 3)
            Caption("Only the tickers you watch (and what you type into the add box) are sent to Yahoo Finance, nothing from your messages.", maxLines = 4)
            Spacer(Modifier.height(Spacing.lg))
        }
    }

    val chartRow = chartSymbol?.let { symbol -> state.rows.firstOrNull { it.symbol == symbol } }
    if (chartRow != null) StockSheet(chartRow, onDismiss = { chartSymbol = null })

    if (editing) {
        FormSheet(
            title = "Edit watch list",
            onDismiss = { editing = false },
            asPage = true,
            primaryLabel = "Done",
            primaryEnabled = true,
            onPrimary = { editing = false }
        ) { WatchlistEditor() }
    }
}
