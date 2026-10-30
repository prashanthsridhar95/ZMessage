package com.zyke.message.presentation.markets

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.markets.MarketTicker
import com.zyke.message.domain.markets.MarketKind
import com.zyke.message.domain.markets.MarketRow
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.home.Danger
import com.zyke.message.presentation.home.Good
import com.zyke.message.presentation.home.SectionHeader
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

internal fun priceText(row: MarketRow): String = when {
    row.currency == "INR" && row.kind == MarketKind.COMMODITY -> "₹" + indian(row.price, 0)
    row.currency == "INR" -> "₹" + indian(row.price, 2)
    row.currency == "USD" -> "$" + NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(row.price)
    row.currency.isNotEmpty() -> row.currency + " " + indian(row.price, 2)
    else -> indian(row.price, 2)
}

private fun indian(value: BigDecimal, digits: Int): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply { minimumFractionDigits = digits; maximumFractionDigits = digits }.format(value)

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
@Composable
fun MarketStrip(onOpen: () -> Unit) {
    val ticker = LocalContext.current.marketTicker()
    val state by ticker.state.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(16.dp)
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
            val listState = rememberLazyListState()
            val pixels = with(LocalDensity.current) { SCROLL_DP_PER_SECOND.dp.toPx() }
            LaunchedEffect(rows.size) {
                var last = 0L
                while (true) {
                    val now = withFrameNanos { it }
                    if (last != 0L) listState.scrollBy(pixels * (now - last) / 1_000_000_000f)
                    last = now
                }
            }
            LazyRow(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = "Market watch. " + rows.joinToString(". ") { spoken(it) }
                },
                state = listState,
                userScrollEnabled = false,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The list is repeated so the band never runs out; the position is the repeat's remainder.
                items(count = Int.MAX_VALUE / 2) { index ->
                    val row = rows[index % rows.size]
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Text(priceText(row), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Text(changeText(row), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = moveColor(row), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Every price in the watch list: the dollar and gold, the indices, and the person's shares, with how each moved today. */
@Composable
fun MarketPage(onBack: () -> Unit) {
    val ticker = LocalContext.current.marketTicker()
    val state by ticker.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val at = state.updatedAt?.let { DateTimeFormatter.ofPattern("h:mm:ss a", Locale.getDefault()).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                    Text(
                        if (at != null) "Updated $at" else if (state.offline) "Not updated yet" else "Loading…",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                    )
                    Caption("Refreshes every 20 seconds while this is open. Prices can be delayed by up to 15 minutes.", maxLines = 3)
                }
                AppButton("Refresh", { scope.launch { ticker.refresh() } }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, enabled = !state.loading)
            }
            if (state.offline) StatusPill(if (state.rows.isEmpty()) "Can't reach the price service" else "Offline · showing the last prices", Tone.WARN)

            val groups = listOf(
                "Currency and gold" to state.rows.filter { it.kind == MarketKind.CURRENCY || it.kind == MarketKind.COMMODITY },
                "Indices" to state.rows.filter { it.kind == MarketKind.INDEX },
                "Your shares" to state.rows.filter { it.kind == MarketKind.STOCK }
            )
            groups.filter { it.second.isNotEmpty() }.forEach { (title, rows) ->
                SectionHeader(title, rows.size)
                rows.forEach { MarketCard(it) }
            }
            Caption("Choose what to watch in Settings › Market watch. Only the names of what you watch are sent, nothing from your messages.", Modifier.padding(top = 4.dp), maxLines = 4)
            // Room for the button to sit below the last card.
            Spacer(Modifier.height(72.dp))
        }
    }

    if (editing) {
        com.zyke.message.presentation.common.FormSheet(
            title = "Edit watch list",
            onDismiss = { editing = false },
            primaryLabel = "Done",
            primaryEnabled = true,
            onPrimary = { editing = false }
        ) { WatchlistEditor() }
    }
}

@Composable
private fun MarketCard(row: MarketRow) {
    FintechCard(Modifier.clearAndSetSemantics { contentDescription = spoken(row) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(row.label)
                Caption(row.detail)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(priceText(row), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                val tone = when {
                    row.isUp -> Tone.GOOD
                    row.isDown -> Tone.BAD
                    else -> Tone.NEUTRAL
                }
                row.change?.let { change ->
                    StatusPill(changeText(row) + "  " + (if (change.signum() >= 0) "+" else "−") + indian(change.abs(), 2), tone, Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
