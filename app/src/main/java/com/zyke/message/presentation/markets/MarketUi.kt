package com.zyke.message.presentation.markets

import android.content.Context
import com.zyke.message.presentation.format.FormatCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.zyke.message.data.markets.HistoryOutcome
import com.zyke.message.domain.markets.ChartRange
import com.zyke.message.presentation.home.ChevronTurn
import com.zyke.message.presentation.motion.AppearVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.markets.MarketTicker
import com.zyke.message.domain.markets.CurrencySymbols
import com.zyke.message.domain.markets.MarketKind
import com.zyke.message.domain.markets.MarketRow
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.home.Danger
import com.zyke.message.presentation.home.Good
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REFRESH_MS = 20_000L
private const val SCROLL_DP_PER_SECOND = 36f

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

private fun changeText(row: MarketRow): String {
    val percent = row.changePercent ?: return "no change shown"
    val arrow = when {
        row.isUp -> "▲ "
        row.isDown -> "▼ "
        else -> ""
    }
    return arrow + percent.abs().toPlainString() + "%"
}

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

/**
 * A slim band on Home that scrolls the watch list past, like a ticker. Tapping it opens the full list. The band says
 * what it holds to a screen reader in one sentence, and its text always carries an arrow, not only a colour.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MarketStrip(onOpen: () -> Unit) {
    val ticker = LocalContext.current.marketTicker()
    val state by ticker.state.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(16.dp)
    val still = com.zyke.message.presentation.common.rememberReduceMotion()
    val indices = state.rows.filter { it.kind == MarketKind.INDEX }.take(2)
    if (indices.isNotEmpty()) {
        // Like Kite's header: the indices on one compact line, no card round them. Tapping still opens the full list.
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open market watch", onClick = onOpen)
                .clearAndSetSemantics {
                    contentDescription = "Market watch. " + indices.joinToString(". ") { spoken(it) }
                    role = Role.Button
                    onClick(label = "Open market watch") { onOpen(); true }
                }
        ) {
            IndexLine(indices, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp))
            WatchDivider()
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClickLabel = "Open market watch", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rows = state.rows
        if (rows.isEmpty()) {
            Caption(
                if (state.offline) "Can't reach the price service · tap for details" else "Loading prices…",
                Modifier.padding(horizontal = 16.dp)
            )
        } else {
            // No index to put in the header: one row of everything, drawn once, with the system's own marquee moving it.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = "Market watch. " + rows.joinToString(". ") { spoken(it) } }
                    // Lite mode (or reduced motion): nothing scrolls by itself; the strip is swiped by hand instead.
                    .then(
                        if (still) Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        else Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, spacing = MarqueeSpacing(24.dp), velocity = SCROLL_DP_PER_SECOND.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rows.forEach { row ->
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(8.dp))
                        Text(priceText(row), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(6.dp))
                        Text(changeText(row), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = moveColor(row), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

private val TABULAR = "tnum"

@Composable
private fun WatchDivider() = HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

/** "+416.15" with a real minus sign, or null when the service gave no previous close. */
private fun signedChange(row: MarketRow): String? =
    row.change?.let { (if (it.signum() >= 0) "+" else "\u2212") + changeAmount(row, it.abs()) }

private fun signedPercent(row: MarketRow): String? =
    row.changePercent?.let { "(" + (if (it.signum() >= 0) "+" else "\u2212") + it.abs().toPlainString() + "%)" }

/** "▲ +416.15 (+1.64%)": the arrow and the signs say it without the colour. Blank when nothing is known. */
private fun moveLine(row: MarketRow): String {
    val arrow = when {
        row.isUp -> "\u25B2"
        row.isDown -> "\u25BC"
        else -> null
    }
    return listOfNotNull(arrow, signedChange(row), signedPercent(row)).joinToString(" ")
}

/** The small grey line under a name: its exchange, or for the dollar and gold what they measure. Nothing when unknown. */
private fun subLine(row: MarketRow): String =
    row.exchange.ifBlank { if (row.kind == MarketKind.STOCK || row.kind == MarketKind.INDEX) "" else row.detail }

/** The indices side by side: NAME, then the level and how it moved, wrapping rather than clipping when the text is large. */
@Composable
private fun IndexLine(indices: List<MarketRow>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        indices.forEach { row ->
            Column(Modifier.weight(1f)) {
                Text(row.label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val color = moveColor(row)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(priceText(row)) }
                        val move = moveLine(row)
                        if (move.isNotEmpty()) withStyle(SpanStyle(color = color)) { append("  $move") }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
                    maxLines = 3
                )
            }
        }
    }
}

/** The Overview: each index's level, its move and a small 52-week line (only when the service gave the history). */
@Composable
private fun IndexOverview(indices: List<MarketRow>) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Overview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            indices.forEach { row -> Box(Modifier.weight(1f)) { OverviewCell(row) } }
        }
        Caption("Lines show the last 52 weeks.")
    }
}

@Composable
private fun OverviewCell(row: MarketRow) {
    val ticker = LocalContext.current.marketTicker()
    var closes by remember(row.symbol) { mutableStateOf<List<Double>?>(null) }
    LaunchedEffect(row.symbol) {
        val outcome = ticker.history(row.symbol, ChartRange.YEAR)
        closes = (outcome as? HistoryOutcome.Ready)?.history?.points?.map { it.value }?.takeIf { it.size >= 2 }
    }
    val color = moveColor(row)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(row.label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(priceText(row), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        moveLine(row).takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = color)
        }
        closes?.let { YearLine(it, color, Modifier.fillMaxWidth().height(40.dp).padding(top = 6.dp)) }
    }
}

/** A line of the closes, scaled to its own high and low. Drawn once; it says its start and end to a screen reader. */
@Composable
private fun YearLine(values: List<Double>, color: Color, modifier: Modifier) {
    val low = values.min()
    val span = (values.max() - low).takeIf { it > 0.0 } ?: 1.0
    val summary = "52 week trend, from ${"%,.2f".format(Locale.US, values.first())} to ${"%,.2f".format(Locale.US, values.last())}"
    val stroke = with(LocalDensity.current) { 1.5.dp.toPx() }
    Canvas(modifier.clearAndSetSemantics { contentDescription = summary }) {
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - ((v - low) / span).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Every price in the watch list: the dollar and gold, the indices, and the person's shares, with how each moved today. */
@Composable
fun MarketPage(onBack: () -> Unit) {
    val ticker = LocalContext.current.marketTicker()
    val state by ticker.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var overviewOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // The ticker whose chart is open. Cards are only tappable on this page, which is only reachable with the market watch on.
    var chartSymbol by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }

    AppPage(
        "Market watch", onBack,
        floating = {
            androidx.compose.material3.FloatingActionButton(
                onClick = { editing = true },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(com.zyke.message.R.drawable.ic_edit_24),
                    contentDescription = "Edit the watch list"
                )
            }
        }
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val at = state.updatedAt?.let { FormatCache.pattern("h:mm:ss a", Locale.getDefault()).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                    Text(
                        if (at != null) "Updated $at" else if (state.offline) "Not updated yet" else "Loading…",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                    )
                    Caption("Refreshes every 20 seconds while this is open. Prices can be delayed by up to 15 minutes.", maxLines = 3)
                }
                AppButton("Refresh", { scope.launch { ticker.refresh() } }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, enabled = !state.loading)
            }
            if (state.offline) StatusPill(if (state.rows.isEmpty()) "Can't reach the price service" else "Offline · showing the last prices", Tone.WARN)

            val indices = state.rows.filter { it.kind == MarketKind.INDEX }.take(2)
            if (indices.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clearAndSetSemantics {
                                contentDescription = indices.joinToString(". ") { spoken(it) }
                                role = Role.Button
                                onClick(label = if (overviewOpen) "Hide overview" else "Show overview") { overviewOpen = !overviewOpen; true }
                            }
                            .clickable(role = Role.Button, onClickLabel = if (overviewOpen) "Hide overview" else "Show overview") { overviewOpen = !overviewOpen },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IndexLine(indices, Modifier.weight(1f).padding(vertical = 8.dp))
                        ChevronTurn(overviewOpen)
                    }
                    AppearVisibility(overviewOpen) { IndexOverview(indices) }
                    WatchDivider()
                }
            }
            // One dense list in the order the person set: no card per row, a hairline between them.
            Column(Modifier.fillMaxWidth()) {
                state.rows.forEach { row ->
                    WatchRow(row, onOpen = if (row.symbol.isNotEmpty()) ({ chartSymbol = row.symbol }) else null)
                    WatchDivider()
                }
            }
            Caption("Choose what to watch in Settings › Market watch. Only the tickers you watch (and what you type into the add box) are sent to Yahoo Finance, nothing from your messages.", Modifier.padding(top = 4.dp), maxLines = 4)
            // Room for the button to sit below the last card.
            Spacer(Modifier.height(72.dp))
        }
    }

    val chartRow = chartSymbol?.let { symbol -> state.rows.firstOrNull { it.symbol == symbol } }
    if (chartRow != null) StockSheet(chartRow, onDismiss = { chartSymbol = null })

    if (editing) {
        com.zyke.message.presentation.common.FormSheet(
            title = "Edit watch list",
            onDismiss = { editing = false },
            asPage = true,
            primaryLabel = "Done",
            primaryEnabled = true,
            onPrimary = { editing = false }
        ) { WatchlistEditor() }
    }
}

/** One line of the list, Kite-style: name and exchange on the left, price and move on the right, no card. */
@Composable
private fun WatchRow(row: MarketRow, onOpen: (() -> Unit)?) {
    val color = moveColor(row)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clearAndSetSemantics {
                contentDescription = spoken(row)
                if (onOpen != null) {
                    role = Role.Button
                    onClick(label = "Open chart") { onOpen(); true }
                }
            }
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open chart", onClick = onOpen) else Modifier)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subLine(row).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(priceText(row), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = TABULAR), color = color, maxLines = 1)
            moveLine(row).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = mutedColor(), maxLines = 1)
            }
        }
    }
}
