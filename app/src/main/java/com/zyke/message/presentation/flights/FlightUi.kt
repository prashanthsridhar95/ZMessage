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
private fun rememberMinuteState(): State<Long> {
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

/** "Your flight today": up to two flights, then "n more". Styled like the "Needs your attention" card, and as quiet. */
@Composable
internal fun FlightCard(today: FlightsToday, onOpen: (String) -> Unit, onDismiss: (String) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val flights = today.flights
    if (flights.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) flights else flights.take(MAX_CARDS)
    com.zyke.message.presentation.common.FintechCard(modifier) {
        Text(
            if (flights.size == 1) "Your flight today" else "Your flights today",
            Modifier.heading().padding(vertical = 2.dp),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary
        )
        shown.forEachIndexed { index, flight ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            FlightRow(today.snapshot(flight), today, onOpen = { onOpen(flight.key) }, onDismiss = { onDismiss(flight.dismissKey) })
        }
        if (flights.size > MAX_CARDS) {
            val more = flights.size - MAX_CARDS
            AppButton(
                if (expanded) "Show fewer" else if (more == 1) "1 more flight" else "$more more flights",
                { expanded = !expanded }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
            )
        }
        AppButton("Add a flight", onAdd, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
}

@Composable
private fun FlightRow(s: FlightSnapshot, today: FlightsToday, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open flight status", onClick = onOpen).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AirlineChip(s.flight.airline.code)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        FlightText.title(s), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    StatusPill(s.kind, Modifier.padding(top = 4.dp))
                    Text(
                        FlightText.headline(s, today.now), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                        color = onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    cardDetail(s, today)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = onSurface.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Hide for today", onClick = onDismiss), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(
                    modifier = Modifier.size(18.dp),
                    painter = androidx.compose.ui.res.painterResource(com.zyke.message.R.drawable.baseline_close_24),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(onSurface.copy(alpha = 0.8f)),
                    contentDescription = "Hide for today"
                )
            }
        }
        if (!today.liveOn) {
            AppButton("Turn on live status", onOpen, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    }
}

/** Terminal and gate when known, then how fresh the live data is; or where the times come from when there is none. */
private fun cardDetail(s: FlightSnapshot, today: FlightsToday): String? {
    val parts = listOfNotNull(
        FlightText.place(s),
        when {
            s.updatedAt != null -> FlightText.updated(today.now.toEpochMilli(), s.updatedAt!!)
            !today.liveOn -> "From your message"
            else -> null
        }
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun AirlineChip(code: String) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Text(code, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** The status in words, on a tint of its colour with a dot, so it never relies on colour alone. */
@Composable
internal fun StatusPill(kind: FlightStatusKind, modifier: Modifier = Modifier) {
    val color = when (kind) {
        FlightStatusKind.SCHEDULED, FlightStatusKind.CHECK_IN -> AppColors.info
        FlightStatusKind.BOARDING, FlightStatusKind.DEPARTED, FlightStatusKind.IN_AIR, FlightStatusKind.LANDED -> AppColors.success
        FlightStatusKind.DELAYED -> AppColors.warning
        FlightStatusKind.CANCELLED, FlightStatusKind.DIVERTED -> AppColors.danger
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(kind.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
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

    AdaptiveSheet(
        title = "${flight.airline.name} ${flight.code}",
        onDismiss = onDismiss,
        autoPage = false,
        footer = {
            when {
                !today.liveOn -> AppButton("Turn on live status", { service.setLiveEnabled(true) }, Modifier.fillMaxWidth())
                else -> ResponsiveRow(Modifier.fillMaxWidth(), spacing = 12.dp) {
                    AppButton(
                        if (failed) "Retry" else if (refreshing) "Refreshing…" else "Refresh",
                        { scope.launch { service.refresh(listOf(flight), today.zone, manual = true) } },
                        Modifier.cell(), style = if (failed) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE, enabled = !refreshing
                    )
                    if (flight.pnr != null) AppButton("Copy PNR", { copyPnr(context, flight.pnr!!) }, Modifier.cell(), style = ButtonStyle.OUTLINE)
                }
            }
        }
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RouteHeader(snapshot, now)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(snapshot.kind)
                Text(FlightText.headline(snapshot, now), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Times(snapshot)
            Facts(snapshot, flight)
            Freshness(snapshot, today, now, failed, notFound)
            val mine = today.manual.firstOrNull { it.key == flightKey }
            if (mine != null) ManualFlightButtons(mine, onEdit, onDismiss)
        }
    }
}

@Composable
private fun RouteHeader(s: FlightSnapshot, now: Instant) {
    val muted = mutedColor()
    val done = MaterialTheme.colorScheme.primary
    val fraction = FlightText.progress(s, now)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PlaceColumn(s.origin?.label ?: "—", s.origin?.city, Alignment.Start)
        Canvas(
            Modifier.weight(1f).padding(horizontal = 12.dp).height(28.dp)
                .semantics { contentDescription = "Flight progress ${(fraction * 100).toInt()} percent" }
        ) {
            val y = size.height / 2
            val r = 5.dp.toPx()
            val startX = r
            val endX = size.width - r
            val stroke = 3.dp.toPx()
            drawLine(muted.copy(alpha = 0.5f), Offset(startX, y), Offset(endX, y), strokeWidth = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
            val x = startX + (endX - startX) * fraction
            if (fraction > 0f) drawLine(done, Offset(startX, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(done, r, Offset(startX, y))
            drawCircle(if (fraction >= 1f) done else muted, r, Offset(endX, y))
            if (fraction in 0.001f..0.999f) {
                // A small arrow where the flight is.
                val arrow = Path().apply {
                    moveTo(x + 9.dp.toPx(), y)
                    lineTo(x - 6.dp.toPx(), y - 7.dp.toPx())
                    lineTo(x - 3.dp.toPx(), y)
                    lineTo(x - 6.dp.toPx(), y + 7.dp.toPx())
                    close()
                }
                drawPath(arrow, done)
            }
        }
        PlaceColumn(s.destination?.label ?: "—", s.destination?.city, Alignment.End)
    }
}

@Composable
private fun PlaceColumn(code: String, city: String?, align: Alignment.Horizontal) {
    Column(Modifier.widthIn(min = 64.dp, max = 112.dp), horizontalAlignment = align) {
        Text(code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (city != null && city != code) Caption(city, maxLines = 2)
    }
}

@Composable
private fun Times(s: FlightSnapshot) {
    val departed = s.kind in setOf(FlightStatusKind.DEPARTED, FlightStatusKind.IN_AIR, FlightStatusKind.LANDED)
    val landed = s.kind == FlightStatusKind.LANDED
    ResponsiveRow(Modifier.fillMaxWidth(), spacing = 12.dp) {
        TimeCell(
            if (departed) "Departed" else "Departs", s.bestDeparture?.let { FlightText.clock(it, s.departureZone) },
            scheduled = s.scheduledDeparture?.takeIf { it != s.bestDeparture }?.let { FlightText.clock(it, s.departureZone) },
            onTime = s.isLive && s.kind != FlightStatusKind.DELAYED && s.kind != FlightStatusKind.CANCELLED, modifier = Modifier.cell()
        )
        TimeCell(
            if (landed) "Landed" else "Arrives", FlightText.arrivalClock(s),
            scheduled = s.scheduledArrival?.takeIf { it != s.bestArrival }?.let { FlightText.clock(it, s.arrivalZone) },
            onTime = s.isLive && s.kind != FlightStatusKind.DELAYED && s.kind != FlightStatusKind.CANCELLED, modifier = Modifier.cell()
        )
    }
}

@Composable
private fun TimeCell(label: String, time: String?, scheduled: String?, onTime: Boolean, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(14.dp)) {
        Caption(label)
        Text(time ?: "Not known", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (scheduled != null) Caption("Scheduled $scheduled", maxLines = 2) else if (onTime && time != null) Caption("On time")
    }
}

@Composable
private fun Facts(s: FlightSnapshot, flight: BookedFlight) {
    val rows = listOfNotNull(
        s.terminal?.let { "Terminal" to it },
        s.gate?.let { "Gate" to it },
        s.baggage?.let { "Baggage belt" to it },
        s.arrivalTerminal?.let { "Arrival terminal" to it },
        flight.pnr?.let { "PNR" to it }
    )
    if (rows.isEmpty()) {
        Caption("Terminal and gate appear here when your airline or the live status gives them.", maxLines = 3)
        return
    }
    Column {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = mutedColor())
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Where the status comes from and how fresh it is; what went wrong when it could not be refreshed; or that it is off. */
@Composable
private fun Freshness(s: FlightSnapshot, today: FlightsToday, now: Instant, failed: Boolean, notFound: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    when {
        today.demo -> Caption("Demo: a made-up flight whose status moves with the clock. Nothing is asked of the internet.", maxLines = 4)
        !today.liveOn -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Live status is off", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onSurface)
            Caption("These times are from your booking message. Turning it on asks Flightradar24 for delays, gate and landing, sending only the airline code and flight number (like ${s.flight.code}), never your messages or PNR.", maxLines = 6)
        }
        failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (s.updatedAt != null) "Couldn’t refresh · showing last known status" else "Couldn’t get live status · showing your booking",
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
