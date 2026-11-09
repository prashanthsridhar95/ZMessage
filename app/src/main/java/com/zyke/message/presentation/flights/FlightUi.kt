package com.zyke.message.presentation.flights

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.zyke.message.domain.flight.FlightCardModel
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.flights.FlightLive
import com.zyke.message.data.flights.FlightService
import com.zyke.message.domain.flight.BookedFlight
import com.zyke.message.domain.flight.FlightSnapshot
import com.zyke.message.domain.flight.FlightStatusKind
import com.zyke.message.domain.flight.FlightText
import com.zyke.message.domain.flight.FlightTracker
import com.zyke.message.domain.flight.ManualFlights
import com.zyke.message.notifications.FlightTrackingControl
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.messages.components.AirlineLogo
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.theme.AppColors
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the open sheet asks again, while it is showing. Nothing runs when it is closed or the app is in the background. */
private const val SHEET_REFRESH_MS = 30_000L
private const val MAX_CARDS = 2
/** Home looks again at whether a flight is due for tracking this often (it also looks whenever flights or switches change). */
private const val FOREGROUND_CHECK_MS = 60_000L

internal fun Context.flightService(): FlightService = (applicationContext as ZykeMessagesApplication).container.flights

/** What Home needs to draw: today's flights, merged with whatever live data there is, on a clock that moves a minute at a time. */
internal class FlightsToday(
    val flights: List<BookedFlight>,
    val live: FlightLive,
    /** Live status is switched on (or this is the demo, which makes it up). */
    val liveOn: Boolean,
    val demo: Boolean,
    val zone: ZoneId,
    val now: Instant,
    /** Every flight booked around today (texts and added by hand), whether or not it is showing, so an open sheet survives the card going away. */
    val booked: List<BookedFlight>,
    /** The flights added by hand, so the sheet can offer Edit and Remove for those. */
    val manual: List<com.zyke.message.domain.flight.ManualFlight> = emptyList()
) {
    fun snapshot(flight: BookedFlight): FlightSnapshot = FlightTracker.snapshot(flight, live.statuses[flight.key], zone)
}

/** Wakes at each minute boundary while the screen is showing, so "in 2h 10m" stays right without a timer when it is not. */
@Composable
internal fun rememberMinuteState(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = System.currentTimeMillis()
                delay(60_000L - now.longValue % 60_000L)
            }
        }
    }
    return now
}

/**
 * Works out today's flights and, each time Home comes to the front (and the first time it appears), asks once for their live
 * status if that is switched on. Nothing here runs while the app is in the background.
 */
@Composable
internal fun rememberFlightsToday(fromTexts: List<BookedFlight>, dismissed: Set<String>): FlightsToday {
    val context = LocalContext.current
    val service = context.flightService()
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val enabled by service.liveEnabled.collectAsStateWithLifecycle()
    val trackingOn by service.trackingEnabled.collectAsStateWithLifecycle()
    val live by service.live.collectAsStateWithLifecycle()
    val manual by container.manualFlights.flights.collectAsStateWithLifecycle()
    val minute by rememberMinuteState()
    val zone = remember { ZoneId.systemDefault() }
    val now = Instant.ofEpochMilli(minute)
    // Flights found in texts and flights typed in by hand are one list; the same flight in both shows once.
    val booked = remember(fromTexts, manual) { ManualFlights.merge(fromTexts, manual) }
    val today = remember(booked, minute, live.statuses, dismissed) {
        FlightTracker.today(booked, now, zone, live.statuses).filter { it.dismissKey !in dismissed }
    }
    val liveOn = enabled || container.demo
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(today)
    val keys = today.joinToString(",") { it.key }
    LaunchedEffect(keys, liveOn) {
        if (!liveOn || latest.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { service.refresh(latest, zone) }
    }
    // While Home is showing: write the flights down for the alarm, set the alarm, and start the in-flight notification if one is due
    // (a flight in the air, or leaving within half an hour). An activity in front may start a foreground service; nothing here
    // runs while the app is in the background. Never in the demo.
    LaunchedEffect(fromTexts, manual, enabled, trackingOn, minute / FOREGROUND_CHECK_MS) {
        if (!container.demo) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { FlightTrackingControl.onForeground(context, fromTexts) }
    }
    return remember(today, live, liveOn, minute, booked, manual) { FlightsToday(today, live, liveOn, container.demo, zone, now, booked, manual) }
}

// ---- the card on Home

/** At most this many other flights show as one-line rows under the featured one; the rest are behind "n more flights". */
private const val MAX_ROWS = 2

/**
 * "Your flight today": the next flight as a calm boarding-pass strip (airline, status, DEL ——✈—— MAA with times, one supporting
 * line, terminal and gate only when known), any other flights as one-line rows, and one tidy row of actions. Styled like the
 * "Needs your attention" card, and as quiet.
 */
@Composable
internal fun FlightCard(today: FlightsToday, onOpen: (String) -> Unit, onDismiss: (String) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    if (today.flights.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val models = remember(today) { FlightCardModel.featuredFirst(today.flights.map { FlightCardModel.of(today.snapshot(it), today.now, today.liveOn) }) }
    val featured = models.first()
    val others = models.drop(1)
    val shown = if (expanded) others else others.take(MAX_ROWS)
    val stack = rememberStackRoute()
    com.zyke.message.presentation.common.FintechCard(modifier) {
        FeaturedFlight(featured, stack, onOpen = { onOpen(featured.key) }, onDismiss = { onDismiss(featured.dismissKey) })
        shown.forEach { other ->
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            FlightSummaryRow(other, stack) { onOpen(other.key) }
        }
        if (others.size > MAX_ROWS) {
            val more = others.size - MAX_ROWS
            AppButton(
                if (expanded) "Show fewer" else if (more == 1) "1 more flight" else "$more more flights",
                { expanded = !expanded }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
            )
        }
        HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        if (today.liveOn) {
            AppButton("Add a trip", onAdd, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        } else {
            // One row of two equal buttons (stacked, still equal, on a narrow phone or at large text).
            ResponsiveRow(Modifier.fillMaxWidth(), spacing = 8.dp) {
                AppButton("Get live status", { onOpen(featured.key) }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                AppButton("Add a trip", onAdd, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
            }
        }
    }
}

/** The flight to look at: tap anywhere on it for the sheet; the dismiss icon sits in the corner, outside that tap. */
@Composable
private fun FeaturedFlight(m: FlightCardModel, stack: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sideBySide = !stack && LocalDensity.current.fontScale < SIDE_BY_SIDE_MAX_FONT
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Open flight status", onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = m.spoken }
        ) {
            Column(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                AirlineLine(m, logoSize = 44.dp, showPill = !stack, big = false, endPadding = DISMISS_SPACE)
                if (stack) StatusPill(m.kind, Modifier.padding(top = 8.dp))
                RouteBlock(m, big = false, modifier = Modifier.padding(top = 14.dp))
                Text(
                    m.supporting, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (m.chips.isNotEmpty() || m.caption != null) {
                    if (sideBySide) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chips(m.chips)
                            Spacer(Modifier.weight(1f))
                            m.caption?.let { Caption(it, maxLines = 1) }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (m.chips.isNotEmpty()) Chips(m.chips)
                            m.caption?.let { Caption(it, maxLines = 1) }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-2).dp).size(48.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Hide for today", onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                modifier = Modifier.size(16.dp),
                painter = androidx.compose.ui.res.painterResource(com.zyke.message.R.drawable.baseline_close_24),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(onSurface.copy(alpha = 0.8f)),
                contentDescription = "Hide for today"
            )
        }
    }
}

private val DISMISS_SPACE = 36.dp
private const val SIDE_BY_SIDE_MAX_FONT = 1.15f

/** Terminal, gate and the like: small rounded labels, one per known fact; never drawn empty. */
@Composable
private fun Chips(chips: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            Text(
                chip, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
            )
        }
    }
}

/** A flight that is not the featured one, folded to one line: logo, "6E 2345 · DEL → MAA · 18:40", its status. */
@Composable
private fun FlightSummaryRow(m: FlightCardModel, stack: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Open flight status", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = m.spoken },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            AirlineLogo(m.airlineCode, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                m.summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!stack) {
                Spacer(Modifier.width(8.dp))
                Caption(m.statusLabel, maxLines = 1)
            }
        }
    }
}

// ---- the sheet

/**
 * The full status of one flight. While it is open, and the app is in front, it asks again every 30 seconds (the service
 * reuses an answer under 25 seconds old); closing it or leaving the app stops that. It stays a bottom sheet.
 */
@Composable
internal fun FlightSheet(flightKey: String, today: FlightsToday, onDismiss: () -> Unit, onEdit: (String) -> Unit = {}) {
    val flight = today.booked.firstOrNull { it.key == flightKey }
    if (flight == null) {
        LaunchedEffect(flightKey) { onDismiss() }
        return
    }
    val context = LocalContext.current
    val service = context.flightService()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshot = today.snapshot(flight)
    val finished by rememberUpdatedState(snapshot.kind.isFinal)

    LaunchedEffect(flightKey, today.liveOn) {
        if (!today.liveOn) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                service.refresh(listOf(flight), today.zone)
                if (finished) break
                delay(SHEET_REFRESH_MS)
            }
        }
    }
    // The "Updated 12 s ago" text moves each second, only while this is showing.
    val clock by com.zyke.message.presentation.home.rememberNowState()
    val now = Instant.ofEpochMilli(clock)
    val failed = flightKey in today.live.failed
    val notFound = flightKey in today.live.notFound
    val refreshing = today.live.loading
    val model = FlightCardModel.of(snapshot, now, today.liveOn)
    val stack = rememberStackRoute()

    AdaptiveSheet(
        title = "Flight status",
        onDismiss = onDismiss,
        autoPage = false,
        footer = {
            if (!today.liveOn) {
                AppButton("Turn on live status", { service.setLiveEnabled(true) }, Modifier.fillMaxWidth())
            } else {
                AppButton(
                    if (failed) "Retry" else if (refreshing) "Refreshing…" else "Refresh",
                    { scope.launch { service.refresh(listOf(flight), today.zone, manual = true) } },
                    Modifier.fillMaxWidth(), style = if (failed) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE, enabled = !refreshing
                )
            }
        }
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AirlineLine(model, logoSize = 52.dp, showPill = !stack, big = true)
                if (stack) StatusPill(model.kind)
            }
            RouteBlock(model, big = true, showDuration = true)
            Text(model.supporting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Facts(snapshot, flight, stack, onCopyPnr = { copyPnr(context, it) })
            Freshness(snapshot, today, now, failed, notFound, today.live.reasons[flightKey])
            val mine = today.manual.firstOrNull { it.key == flightKey }
            if (mine != null) ManualFlightButtons(mine, onEdit, onDismiss)
        }
    }
}

/** What the sheet knows beyond the route, as tiles in two aligned columns (one column on a narrow phone or at large text). */
@Composable
private fun Facts(s: FlightSnapshot, flight: BookedFlight, stack: Boolean, onCopyPnr: (String) -> Unit) {
    val facts = listOfNotNull(
        s.terminal?.let { "Terminal" to it },
        s.gate?.let { "Gate" to it },
        s.baggage?.let { "Baggage belt" to it },
        s.arrivalTerminal?.let { "Arrival terminal" to it }
    )
    if (facts.isEmpty() && flight.pnr == null) {
        Caption("Terminal and gate appear here when your airline or the live status gives them.", maxLines = 3)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        facts.chunked(if (stack) 1 else 2).forEach { rowFacts ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowFacts.forEach { (label, value) -> FactTile(label, value, Modifier.weight(1f)) }
                if (rowFacts.size == 1 && !stack) Spacer(Modifier.weight(1f))
            }
        }
        flight.pnr?.let { pnr ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Caption("PNR")
                    Text(pnr, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                AppButton("Copy", { onCopyPnr(pnr) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    }
}

@Composable
private fun FactTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Caption(label)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Where the status comes from and how fresh it is; what went wrong when it could not be refreshed; or that it is off. */
@Composable
private fun Freshness(s: FlightSnapshot, today: FlightsToday, now: Instant, failed: Boolean, notFound: Boolean, reason: com.zyke.message.domain.diag.LiveFailure? = null) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    when {
        today.demo -> Caption("Demo: a made-up flight whose status moves with the clock. Nothing is asked of the internet.", maxLines = 4)
        !today.liveOn -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Live status is off", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onSurface)
            Caption("These times are from your booking message. Turning it on asks Flightradar24 for delays, gate and landing, sending only the airline code and flight number (like ${s.flight.code}), never your messages or PNR.", maxLines = 6)
        }
        failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                com.zyke.message.domain.diag.LiveCheck.cardLine(reason, s.updatedAt != null, "flight status"),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onSurface
            )
            s.updatedAt?.let { Caption(FlightText.updated(now.toEpochMilli(), it)) }
        }
        notFound && s.updatedAt == null -> Caption("No live data for this flight yet. It usually appears a few hours before departure. Showing your booking.", maxLines = 4)
        s.updatedAt != null -> Caption(FlightText.updated(now.toEpochMilli(), s.updatedAt!!) + if (s.kind.isFinal) "" else " · refreshes every 30 s", maxLines = 2)
        else -> Caption("Getting live status…")
    }
}

/** Edit and Remove for a flight added by hand: equal width side by side, stacked on a narrow phone. */
@Composable
private fun ManualFlightButtons(flight: com.zyke.message.domain.flight.ManualFlight, onEdit: (String) -> Unit, onClose: () -> Unit) {
    val actions = rememberManualFlightActions()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(if (flight.confirmed) "Added by you · route and times confirmed" else "Added by you · Schedule not confirmed", maxLines = 2)
        ResponsiveRow(Modifier.fillMaxWidth(), spacing = 12.dp) {
            AppButton("Edit", { onEdit(flight.id) }, Modifier.cell(), style = ButtonStyle.OUTLINE)
            AppButton("Remove", { actions.remove(flight); onClose() }, Modifier.cell(), style = ButtonStyle.OUTLINE)
        }
    }
}

private fun copyPnr(context: Context, pnr: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PNR", pnr))
    Toast.makeText(context, "PNR copied", Toast.LENGTH_SHORT).show()
}
