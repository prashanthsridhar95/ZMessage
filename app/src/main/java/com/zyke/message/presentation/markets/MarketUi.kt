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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.zyke.message.presentation.home.homeStacked
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.LocalTrueBlack
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.tonalFill
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
private const val HOME_ROWS = 5
private val ROW_HEIGHT = 56.dp
private val SPARK_HEIGHT = 40.dp

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
private fun statusText(state: com.zyke.message.data.markets.MarketState, pattern: String): String {
    val at = state.updatedAt?.let { clock(pattern, it) }
    return when {
        state.offline && at != null -> "Offline · updated $at"
        state.offline -> "Offline"
        at != null -> "Updated $at"
        state.loading -> "Updating…"
        else -> ""
    }
}

/**
 * The index header: NAME, level and today's move on one line each, left aligned in a column so the levels line up, with a
 * chevron that opens the Overview. Only the percentage takes the green or red.
 */
@Composable
private fun IndexHeader(indices: List<MarketRow>, open: Boolean, onToggle: () -> Unit) {
    val label = if (open) "Hide overview" else "Show overview"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clearAndSetSemantics {
                contentDescription = indices.joinToString(". ") { spoken(it) }
                role = Role.Button
                onClick(label = label) { onToggle(); true }
            }
            .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = Spacing.xs)) {
            indices.forEach { row ->
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label.uppercase(Locale.ROOT), Modifier.weight(1f, fill = false).widthIn(min = 76.dp).padding(end = Spacing.sm),
                        style = MaterialTheme.typography.labelMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        priceText(row), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
                    )
                    signedPercent(row, brackets = false)?.let {
                        Text(
                            it, Modifier.padding(start = Spacing.sm), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR),
                            color = moveColor(row), maxLines = 1, softWrap = false
                        )
                    }
                }
            }
        }
        ChevronTurn(open)
    }
}

/** The Overview: each index's level, today's move and a 52-week line coloured by the year's move (only when the history arrived). */
@Composable
private fun IndexOverview(indices: List<MarketRow>) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xs, bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (homeStacked()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) { indices.forEach { OverviewCell(it) } }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                indices.forEach { row -> Box(Modifier.weight(1f)) { OverviewCell(row) } }
            }
        }
        Caption("Lines show the last 52 weeks.")
    }
}

@Composable
private fun OverviewCell(row: MarketRow) {
    val ticker = LocalContext.current.marketTicker()
    // null while it loads, empty when the service had no history for this one.
    var closes by remember(row.symbol) { mutableStateOf<List<Double>?>(null) }
    LaunchedEffect(row.symbol) {
        val outcome = ticker.history(row.symbol, ChartRange.YEAR)
        closes = (outcome as? HistoryOutcome.Ready)?.history?.points?.map { it.value }?.takeIf { it.size >= 2 } ?: emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(row.label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(priceText(row), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        moveLine(row).takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = moveColor(row), maxLines = 1, softWrap = false)
        }
        val values = closes
        Box(Modifier.fillMaxWidth().height(SPARK_HEIGHT).padding(top = Spacing.sm)) {
            if (!values.isNullOrEmpty()) {
                val yearUp = values.last() >= values.first()
                YearLine(values, if (yearUp) Good else Danger, Modifier.fillMaxSize())
            } else if (values != null) {
                Caption("No history", Modifier.align(Alignment.CenterStart))
            }
        }
        val values2 = closes
        if (!values2.isNullOrEmpty() && values2.first() != 0.0) {
            val pct = (values2.last() - values2.first()) / values2.first() * 100.0
            val up = pct >= 0
            val ink = if (up) Good else Danger
            Text(
                buildAnnotatedString {
                    append("52 weeks ")
                    withStyle(SpanStyle(color = ink)) { append((if (up) "+" else "−") + "%.2f%%".format(Locale.US, kotlin.math.abs(pct))) }
                },
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = mutedColor(), maxLines = 1
            )
        }
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

/** One line of the list, Kite-style: name and exchange on the left, price and move on the right, no card, one 56 dp height. */
@Composable
private fun WatchRow(row: MarketRow, onOpen: (() -> Unit)?) {
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
            }
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open chart", onClick = onOpen) else Modifier)
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
 * The watch card on Home, like Kite's list: the index header (its chevron opens the Overview), the first few watched
 * names as dense rows, a hairline between each, and a quiet "See all" for the full page. A row opens that name's sheet.
 */
@Composable
fun MarketStrip(onOpen: () -> Unit) {
    val ticker = LocalContext.current.marketTicker()
    val state by ticker.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var overviewOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var chartSymbol by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val indices = state.rows.filter { it.kind == MarketKind.INDEX }.take(2)
    val names = state.rows.filter { it.kind == MarketKind.STOCK }.ifEmpty { state.rows.filter { it.kind != MarketKind.INDEX } }.take(HOME_ROWS)
    val trueBlack = LocalTrueBlack.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(tonalFill(Tonal.Quiet))
            .then(if (trueBlack) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), AppShapes.card) else Modifier)
            .padding(horizontal = Spacing.lg)
    ) {
        if (indices.isNotEmpty()) {
            IndexHeader(indices, overviewOpen) { overviewOpen = !overviewOpen }
            AppearVisibility(overviewOpen) { IndexOverview(indices) }
            WatchDivider()
        }
        when {
            names.isNotEmpty() -> names.forEachIndexed { i, row ->
                WatchRow(row, onOpen = if (row.symbol.isNotEmpty()) ({ chartSymbol = row.symbol }) else null)
                if (i < names.lastIndex) WatchDivider()
            }
            state.rows.isNotEmpty() -> Unit
            state.offline || state.updatedAt != null -> WatchEmpty(state.offline, state.loading) { scope.launch { ticker.refresh() } }
            else -> WatchSkeleton()
        }
        if (state.rows.isNotEmpty()) WatchDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption(statusText(state, "h:mm a"), Modifier.weight(1f).padding(end = Spacing.md), maxLines = 1)
            Text(
                "See all ›",
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open market watch", onClick = onOpen)
                    .padding(start = Spacing.sm)
                    .wrapContentHeight(Alignment.CenterVertically),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false
            )
        }
    }
    val chartRow = chartSymbol?.let { symbol -> state.rows.firstOrNull { it.symbol == symbol } }
    if (chartRow != null) StockSheet(chartRow, onDismiss = { chartSymbol = null })
}

/** Every price in the watch list: the indices up top, then the dollar, gold and the person's shares, with how each moved today. */
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption(statusText(state, "h:mm:ss a"), Modifier.weight(1f).padding(end = Spacing.md), maxLines = 2)
                AppButton("Refresh", { scope.launch { ticker.refresh() } }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, enabled = !state.loading)
            }
            if (state.offline && state.rows.isNotEmpty()) StatusPill("Offline · showing the last prices", Tone.WARN)

            val indices = state.rows.filter { it.kind == MarketKind.INDEX }.take(2)
            Column(Modifier.fillMaxWidth()) {
                if (indices.isNotEmpty()) {
                    IndexHeader(indices, overviewOpen) { overviewOpen = !overviewOpen }
                    AppearVisibility(overviewOpen) { IndexOverview(indices) }
                    WatchDivider()
                }
                // One dense list in the order the person set: no card per row, a hairline between them.
                if (state.rows.isEmpty()) {
                    if (state.offline || state.updatedAt != null) WatchEmpty(state.offline, state.loading) { scope.launch { ticker.refresh() } }
                    else WatchSkeleton(6)
                }
                state.rows.forEach { row ->
                    WatchRow(row, onOpen = if (row.symbol.isNotEmpty()) ({ chartSymbol = row.symbol }) else null)
                    WatchDivider()
                }
            }
            Caption("Refreshes every 20 seconds while this is open. Prices can be delayed by up to 15 minutes.", maxLines = 3)
            Caption("Choose what to watch in Settings › Market watch. Only the tickers you watch (and what you type into the add box) are sent to Yahoo Finance, nothing from your messages.", maxLines = 4)
            // Room for the button to sit below the last row.
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
