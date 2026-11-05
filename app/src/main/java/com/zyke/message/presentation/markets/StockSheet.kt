package com.zyke.message.presentation.markets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.domain.markets.ChartRange
import com.zyke.message.domain.markets.CurrencySymbols
import com.zyke.message.domain.markets.MarketKind
import com.zyke.message.domain.markets.MarketRow
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.PriceHistories
import com.zyke.message.domain.markets.PriceHistory
import com.zyke.message.domain.markets.PricePoint
import com.zyke.message.domain.markets.VolumeText
import com.zyke.message.data.markets.HistoryOutcome
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.Danger
import com.zyke.message.presentation.home.Good
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/* Public market prices, not the person's money: like the rest of the market screens this uses Material's Text on purpose
 * (AmountPrivacyTest lists the file), so a price is never dotted out while amounts are hidden. */

private val CHART_HEIGHT = 208.dp
private val INDIAN_LOCALE: Locale = Locale.forLanguageTag("en-IN")
private const val DASH = "–"

/** What the chart area is showing. */
private sealed interface Load {
    data object Loading : Load
    data class Ready(val history: PriceHistory, val stale: Boolean) : Load
    data object Failed : Load
    data object NoData : Load
    data object Off : Load
}

/** How numbers for one ticker are written: its currency sign, grouping and digits. */
private class MoneyFormat(row: MarketRow) {
    private val kind = row.kind
    private val code: String = if (kind == MarketKind.INDEX || (kind == MarketKind.CURRENCY && row.symbol != MarketSymbols.USD_INR)) "" else row.currency
    private val indian = code == "INR" || row.symbol.startsWith("^NSE") || row.symbol.startsWith("^BSE") || row.symbol.startsWith("^CNX")

    fun price(value: Double, digitsOverride: Int? = null): String {
        val digits = digitsOverride ?: when {
            kind == MarketKind.CURRENCY && code.isEmpty() -> 4
            else -> CurrencySymbols.digits(code, BigDecimal.valueOf(value))
        }
        val locale = if (indian) INDIAN_LOCALE else Locale.US
        return CurrencySymbols.prefix(code) + FormatCache.number(locale, digits, digits).format(value) + CurrencySymbols.suffix(code)
    }

    /** A move, with its sign and without the currency sign: "+10.50". */
    fun move(value: Double, like: Double): String {
        val digits = if (kind == MarketKind.CURRENCY && code.isEmpty()) 4 else CurrencySymbols.digits(code, BigDecimal.valueOf(like))
        val locale = if (indian) INDIAN_LOCALE else Locale.US
        return (if (value >= 0) "+" else "−") + FormatCache.number(locale, digits, digits).format(abs(value))
    }

    fun axis(value: Double, digits: Int): String {
        val locale = if (indian) INDIAN_LOCALE else Locale.US
        return FormatCache.number(locale, digits, digits).format(value)
    }

    val line: String
        get() = when (kind) {
            MarketKind.INDEX -> "Index"
            MarketKind.CURRENCY -> "Exchange rate"
            else -> code
        }
}

/**
 * The sheet that opens when a stock, index or the dollar is tapped in the market watch: price, change, a chart for the
 * period picked, the day's figures and a note on where they come from. Everything about the chart is asked for only
 * now, only for this ticker, and only while the market watch is on (see [com.zyke.message.data.markets.MarketTicker.history]).
 * Always a sheet (never a page): it scrolls inside when the screen is short.
 */
@Composable
fun StockSheet(row: MarketRow, onDismiss: () -> Unit) {
    val ticker = LocalContext.current.marketTicker()
    val money = remember(row.symbol, row.currency, row.kind) { MoneyFormat(row) }
    var rangeIndex by rememberSaveable { mutableIntStateOf(ChartRange.DAY.ordinal) }
    val range = ChartRange.entries[rangeIndex.coerceIn(0, ChartRange.entries.lastIndex)]
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    // The last chart that loaded, for the figures a longer period does not carry (open, previous close) and the header while the next one loads.
    var lastGood by remember { mutableStateOf<PriceHistory?>(null) }
    var dayChart by remember { mutableStateOf<PriceHistory?>(null) }

    LaunchedEffect(row.symbol, range, attempt) {
        load = Load.Loading
        when (val outcome = ticker.history(row.symbol, range, force = attempt > 0)) {
            is HistoryOutcome.Ready -> {
                if (range == ChartRange.DAY) dayChart = outcome.history
                val merged = outcome.history.withStatsFrom(dayChart ?: lastGood)
                lastGood = merged
                load = Load.Ready(merged, outcome.stale)
            }
            HistoryOutcome.NoData -> load = Load.NoData
            HistoryOutcome.Unreachable -> load = Load.Failed
            HistoryOutcome.Off -> load = Load.Off
        }
    }

    val shown = (load as? Load.Ready)?.history
    val name = (shown ?: lastGood)?.name?.takeIf { it.isNotBlank() } ?: row.label
    var scrub by remember(shown) { mutableStateOf<PricePoint?>(null) }

    AdaptiveSheet(
        title = MarketSymbols.defaultLabel(row.symbol).ifBlank { row.label },
        onDismiss = onDismiss,
        autoPage = false,
        subtitle = name
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Header(row, money, shown, lastGood, range, scrub, loading = load is Load.Loading)
            Chart(load, range, money, onScrub = { scrub = it }, onRetry = { attempt++ })
            RangeChips(range) { rangeIndex = it.ordinal }
            Stats(shown ?: lastGood, money)
            Text(
                "Data from Yahoo Finance · may be delayed",
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = mutedColor(), textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun Header(row: MarketRow, money: MoneyFormat, shown: PriceHistory?, lastGood: PriceHistory?, range: ChartRange, scrub: PricePoint?, loading: Boolean) {
    val source = shown ?: lastGood
    val now = scrub?.value ?: source?.last
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PriceText(if (now != null) money.price(now) else DASH)
        when {
            scrub != null && shown != null -> {
                Text(
                    PriceHistories.momentLabel(shown, scrub.time), style = MaterialTheme.typography.bodyMedium,
                    color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            shown != null && shown.change != null -> {
                val change = shown.change ?: 0.0
                val color = when {
                    change > 0 -> Good
                    change < 0 -> Danger
                    else -> mutedColor()
                }
                val arrow = if (change > 0) "▲ " else if (change < 0) "▼ " else ""
                val percent = shown.changePercent?.let { " (" + FormatCache.number(Locale.US, 2, 2).format(abs(it)) + "%)" }.orEmpty()
                val text: AnnotatedString = buildAnnotatedString {
                    withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(arrow + money.move(change, shown.last ?: 1.0) + percent) }
                    withStyle(SpanStyle(color = mutedColor())) { append("  " + range.period) }
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            loading -> Block(Modifier.fillMaxWidth(0.5f).height(18.dp), "Loading")
            else -> Text(range.period, style = MaterialTheme.typography.bodyMedium, color = mutedColor())
        }
        val where = listOf(source?.exchange?.ifBlank { row.exchange } ?: row.exchange, money.line).filter { it.isNotBlank() }.joinToString(" · ")
        if (where.isNotBlank()) Text(where, style = MaterialTheme.typography.labelMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The big price. If it still does not fit in one line (a narrow phone with large text) it shrinks a step at a time rather than wrapping. */
@Composable
private fun PriceText(text: String) {
    var scale by remember(text.length) { mutableFloatStateOf(1f) }
    Text(
        text, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        fontSize = MaterialTheme.typography.displaySmall.fontSize * scale,
        maxLines = 1, softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow && scale > 0.45f) scale *= 0.9f }
    )
}

/** A quiet grey stand-in for something still loading (no pulse: nothing loops, so Lite mode and reduced motion are the same as normal). */
@Composable
private fun Block(modifier: Modifier, description: String) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clearAndSetSemantics { contentDescription = description }
    )
}

@Composable
private fun Chart(load: Load, range: ChartRange, money: MoneyFormat, onScrub: (PricePoint?) -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (load) {
            Load.Loading -> Block(Modifier.fillMaxWidth().height(CHART_HEIGHT), "Loading the chart")
            is Load.Ready -> {
                if (load.history.hasChart) {
                    val up = (load.history.change ?: 0.0) >= 0.0
                    PriceChart(load.history, if (up) Good else Danger, money, onScrub, Modifier.fillMaxWidth().height(CHART_HEIGHT))
                    if (load.stale) Text("Offline · showing the last chart", style = MaterialTheme.typography.labelMedium, color = mutedColor())
                } else {
                    Message("Not enough prices for this period yet", "Try a longer one.", null, onRetry)
                }
            }
            Load.Failed -> Message("Couldn't load the chart", "Check your connection and try again.", "Retry", onRetry)
            Load.NoData -> Message("No chart for this ticker", "Yahoo Finance has no prices for it.", null, onRetry)
            Load.Off -> Message("Market watch is off", "Switch it on in Settings to see charts.", null, onRetry)
        }
    }
}

@Composable
private fun Message(title: String, detail: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(CHART_HEIGHT).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, maxLines = 2)
        Text(detail, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = mutedColor(), textAlign = TextAlign.Center, maxLines = 3)
        if (action != null) {
            AppButton(action, onAction, Modifier.padding(top = 12.dp), style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
        }
    }
}

/** The period picker: one row, one choice, never wrapped. It scrolls sideways when the chips do not fit, and brings the chosen one into view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RangeChips(selected: ChartRange, onSelect: (ChartRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        ChartRange.entries.forEach { option ->
            val chosen = option == selected
            val bring = remember { BringIntoViewRequester() }
            if (chosen) LaunchedEffect(option) { bring.bringIntoView() }
            Box(
                Modifier
                    .bringIntoViewRequester(bring)
                    .heightIn(min = 40.dp).widthIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                    color = if (chosen) MaterialTheme.colorScheme.primary else mutedColor()
                )
            }
        }
    }
}

/** Open, high, low and the rest, as label and value on one line each, in two columns when there is room and one when there is not. */
@Composable
private fun Stats(h: PriceHistory?, money: MoneyFormat) {
    fun p(v: Double?) = v?.let { money.price(it) } ?: DASH
    val cells = listOf(
        "Open" to p(h?.open), "Prev close" to p(h?.previousClose),
        "High" to p(h?.dayHigh), "Low" to p(h?.dayLow),
        "52W high" to p(h?.high52), "52W low" to p(h?.low52),
        "Volume" to (h?.volume?.let { VolumeText.short(it) } ?: DASH)
    )
    val big = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val single = big || maxWidth < 300.dp
        if (single) {
            Column(Modifier.fillMaxWidth()) { cells.forEachIndexed { i, c -> StatLine(c.first, c.second, i < cells.lastIndex) } }
        } else {
            Column(Modifier.fillMaxWidth()) {
                cells.chunked(2).forEachIndexed { i, pair ->
                    val last = i == (cells.size - 1) / 2
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.weight(1f)) { StatLine(pair[0].first, pair[0].second, !last) }
                        Box(Modifier.weight(1f)) { if (pair.size > 1) StatLine(pair[1].first, pair[1].second, !last) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, divider: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    }
}

/**
 * One Canvas: grid, price labels on the right, date labels underneath, the line with a soft fill, and a marker that
 * follows a finger dragged across it. At most 200 points are drawn (the history is downsampled when read).
 */
@Composable
private fun PriceChart(history: PriceHistory, color: Color, money: MoneyFormat, onScrub: (PricePoint?) -> Unit, modifier: Modifier) {
    val points = history.points
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val muted = mutedColor()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted, fontSize = 11.sp)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val markerRing = MaterialTheme.colorScheme.surface
    val baseline = history.baseline?.takeIf { history.range == ChartRange.DAY }

    val plot = remember(history, labelStyle, density) {
        var lo = points.minOf { it.value }
        var hi = points.maxOf { it.value }
        if (baseline != null) { lo = minOf(lo, baseline); hi = maxOf(hi, baseline) }
        if (hi - lo < 1e-9) { val pad = abs(hi) * 0.01 + 0.01; lo -= pad; hi += pad }
        val pad = (hi - lo) * 0.08
        lo -= pad; hi += pad
        val ticks = PriceHistories.niceTicks(lo, hi, 4)
        val digits = PriceHistories.tickDigits(ticks)
        val yLabels = ticks.map { it to measurer.measure(money.axis(it, digits), labelStyle) }
        val xLabels = PriceHistories.xLabels(history).map { it to measurer.measure(it.text, labelStyle) }
        val axisWidth = (yLabels.maxOfOrNull { it.second.size.width } ?: 0) + with(density) { 8.dp.toPx() }
        val labelHeight = (xLabels.maxOfOrNull { it.second.size.height } ?: 0) + with(density) { 6.dp.toPx() }
        PlotModel(lo, hi, yLabels, xLabels, axisWidth, labelHeight)
    }

    var marker by remember(history) { mutableIntStateOf(-1) }
    val report = rememberUpdatedState(onScrub)
    val summary = remember(history) {
        val hiPoint = points.maxOf { it.value }
        val loPoint = points.minOf { it.value }
        "Price chart, ${history.range.period}. From ${money.price(points.first().value)} to ${money.price(points.last().value)}, " +
            "high ${money.price(hiPoint)}, low ${money.price(loPoint)}."
    }

    Canvas(
        modifier
            .semantics { contentDescription = summary }
            .pointerInput(history, plot) {
                val width = size.width - plot.axisWidth
                fun at(x: Float) {
                    val i = ((x / width) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                    if (i != marker) { marker = i; report.value(points[i]) }
                }
                detectHorizontalDragGestures(
                    onDragStart = { at(it.x) },
                    onDragEnd = { marker = -1; report.value(null) },
                    onDragCancel = { marker = -1; report.value(null) },
                    onHorizontalDrag = { change, _ -> change.consume(); at(change.position.x) }
                )
            }
    ) {
        val w = size.width - plot.axisWidth
        val h = size.height - plot.labelHeight
        val span = plot.hi - plot.lo
        fun y(v: Double) = (h - ((v - plot.lo) / span * h)).toFloat()
        fun x(i: Int) = if (points.size < 2) 0f else i * w / (points.size - 1)

        plot.yLabels.forEach { (tick, layout) ->
            val ty = y(tick)
            drawLine(gridColor, Offset(0f, ty), Offset(w, ty), strokeWidth = 1f)
            drawText(layout, topLeft = Offset(w + 6.dp.toPx(), (ty - layout.size.height / 2f).coerceIn(0f, h - layout.size.height)))
        }
        plot.xLabels.forEach { (label, layout) ->
            val left = (label.fraction * w - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(left, h + 6.dp.toPx()))
        }
        if (baseline != null) {
            val by = y(baseline)
            drawLine(muted.copy(alpha = 0.6f), Offset(0f, by), Offset(w, by), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        }
        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(i), y(p.value)) else line.lineTo(x(i), y(p.value)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(points.size - 1), h)
            lineTo(0f, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)), startY = 0f, endY = h))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val m = marker
        if (m in points.indices) {
            val mx = x(m)
            drawLine(muted.copy(alpha = 0.7f), Offset(mx, 0f), Offset(mx, h), strokeWidth = 1.dp.toPx())
            drawCircle(markerRing, 6.dp.toPx(), Offset(mx, y(points[m].value)))
            drawCircle(color, 4.dp.toPx(), Offset(mx, y(points[m].value)))
        }
    }
}

private class PlotModel(
    val lo: Double,
    val hi: Double,
    val yLabels: List<Pair<Double, androidx.compose.ui.text.TextLayoutResult>>,
    val xLabels: List<Pair<com.zyke.message.domain.markets.AxisLabel, androidx.compose.ui.text.TextLayoutResult>>,
    val axisWidth: Float,
    val labelHeight: Float
) {
    val span: Double get() = hi - lo
}
