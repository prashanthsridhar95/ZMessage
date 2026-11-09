package com.zyke.message.presentation.trains

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.zyke.message.data.trains.TrainLiveState
import com.zyke.message.data.trains.TrainService
import com.zyke.message.domain.flight.FlightText
import com.zyke.message.domain.train.BookedTrain
import com.zyke.message.domain.train.ManualTrains
import com.zyke.message.domain.train.TrainCardModel
import com.zyke.message.domain.train.TrainSnapshot
import com.zyke.message.domain.train.TrainStatusKind
import com.zyke.message.domain.train.TrainStop
import com.zyke.message.domain.train.TrainText
import com.zyke.message.domain.train.TrainTracker
import com.zyke.message.notifications.TrainTrackingControl
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.flights.rememberMinuteState
import com.zyke.message.presentation.flights.rememberStackRoute
import com.zyke.message.presentation.messages.components.Caption
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the open sheet asks again, while it is showing. Nothing runs when it is closed or the app is in the background. */
private const val SHEET_REFRESH_MS = 30_000L
/** Home looks again at whether a journey is due for tracking this often (it also looks whenever journeys or switches change). */
private const val FOREGROUND_CHECK_MS = 60_000L
private const val MAX_ROWS = 2
private const val STOPS_COLLAPSED = 4
private val DISMISS_SPACE = 36.dp
private const val SIDE_BY_SIDE_MAX_FONT = 1.15f
private const val SIDE_BY_SIDE_MAX_CHIP_CHARS = 12

internal fun Context.trainService(): TrainService = (applicationContext as ZykeMessagesApplication).container.trains

/** What Home needs to draw: today's journeys, merged with whatever live data there is, on a clock that moves a minute at a time. */
internal class TrainsToday(
    val trains: List<BookedTrain>,
    val live: TrainLiveState,
    /** Live train status is switched on (or this is the demo, which makes it up). */
    val liveOn: Boolean,
    val demo: Boolean,
    val now: Instant,
    /** Every journey booked around today (texts and added by hand), whether or not it is showing, so an open sheet survives the card going away. */
    val booked: List<BookedTrain>,
    /** The journeys added by hand, so the sheet can offer Edit and Remove for those. */
    val manual: List<com.zyke.message.domain.train.ManualTrain> = emptyList()
) {
    fun snapshot(train: BookedTrain): TrainSnapshot = TrainTracker.snapshot(train, live.statuses[train.key], now)
}

/**
 * Works out today's journeys and, each time Home comes to the front (and the first time it appears), asks once for their live status
 * if that is switched on. Nothing here runs while the app is in the background.
 */
@Composable
internal fun rememberTrainsToday(fromTexts: List<BookedTrain>, dismissed: Set<String>): TrainsToday {
    val context = LocalContext.current
    val service = context.trainService()
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val enabled by service.liveEnabled.collectAsStateWithLifecycle()
    val trackingOn by service.trackingEnabled.collectAsStateWithLifecycle()
    val live by service.live.collectAsStateWithLifecycle()
    val manual by container.manualTrains.trains.collectAsStateWithLifecycle()
    val minute by rememberMinuteState()
    val now = Instant.ofEpochMilli(minute)
    val booked = remember(fromTexts, manual) { ManualTrains.merge(fromTexts, manual) }
    val today = remember(booked, minute, live.statuses, dismissed) {
        TrainTracker.today(booked, now, live.statuses).filter { it.dismissKey !in dismissed }
    }
    val liveOn = enabled || container.demo
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(today)
    val keys = today.joinToString(",") { it.key }
    LaunchedEffect(keys, liveOn) {
        if (!liveOn || latest.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { service.refresh(latest) }
    }
    // While Home is showing: write the journeys down for the alarm, set the alarm, and start the notification if one is due. An activity
    // in front may start a foreground service; nothing here runs while the app is in the background. Never in the demo.
    LaunchedEffect(fromTexts, manual, enabled, trackingOn, minute / FOREGROUND_CHECK_MS) {
        if (!container.demo) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { TrainTrackingControl.onForeground(context, fromTexts) }
    }
    return remember(today, live, liveOn, minute, booked, manual) { TrainsToday(today, live, liveOn, container.demo, now, booked, manual) }
}

// ---- the card on Home

/**
 * "Your train today": the next journey as a calm strip (train tile, number and name, status, the route with times, one supporting line
 * and one quieter one, chips only for what is known), any other journeys as one-line rows, and one tidy row of actions. Styled like
 * the flight card.
 */
@Composable
internal fun TrainCard(today: TrainsToday, onOpen: (String) -> Unit, onDismiss: (String) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    if (today.trains.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val models = remember(today) { TrainCardModel.featuredFirst(today.trains.map { TrainCardModel.of(today.snapshot(it), today.now, today.liveOn) }) }
    val featured = models.first()
    val others = models.drop(1)
    val shown = if (expanded) others else others.take(MAX_ROWS)
    val stack = rememberStackRoute()
    FintechCard(modifier) {
        FeaturedTrain(featured, stack, onOpen = { onOpen(featured.key) }, onDismiss = { onDismiss(featured.dismissKey) })
        shown.forEach { other ->
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            TrainSummaryRow(other, stack) { onOpen(other.key) }
        }
        if (others.size > MAX_ROWS) {
            val more = others.size - MAX_ROWS
            AppButton(
                if (expanded) "Show fewer" else if (more == 1) "1 more train" else "$more more trains",
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

/** The journey to look at: tap anywhere on it for the sheet; the dismiss icon sits in the corner, outside that tap. */
@Composable
private fun FeaturedTrain(m: TrainCardModel, stack: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val chipChars = m.chips.sumOf { it.length }
    // Chips and the "Updated" line share a row only when both fit comfortably; otherwise the line goes under the chips.
    val sideBySide = !stack && LocalDensity.current.fontScale < SIDE_BY_SIDE_MAX_FONT && chipChars <= SIDE_BY_SIDE_MAX_CHIP_CHARS
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Open train status", onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = m.spoken }
        ) {
            Column(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                TrainLine(m, tileSize = 44.dp, showPill = !stack, big = false, endPadding = DISMISS_SPACE)
                if (stack) TrainPill(m.kind, m.pill, Modifier.padding(top = 8.dp))
                TrainRouteBlock(m, big = false, modifier = Modifier.padding(top = 14.dp))
                Text(
                    m.supporting, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                m.next?.let { Caption(it, Modifier.padding(top = 4.dp), maxLines = 2) }
                if (m.chips.isNotEmpty() || m.caption != null) {
                    if (sideBySide) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrainChips(m.chips)
                            Spacer(Modifier.weight(1f))
                            m.caption?.let { Caption(it, maxLines = 1) }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (m.chips.isNotEmpty()) TrainChips(m.chips)
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

/** Platform, class, coach and berth: small rounded labels, one per known fact; never drawn empty. */
@Composable
private fun TrainChips(chips: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.filter { it.isNotBlank() }.forEach { chip ->
            Text(
                chip, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
            )
        }
    }
}

/** A journey that is not the featured one, folded to one line: tile, "12633 · MS → TPJ · 21:30", its status. */
@Composable
private fun TrainSummaryRow(m: TrainCardModel, stack: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Open train status", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = m.spoken },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            TrainTile(28.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                m.summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!stack) {
                Spacer(Modifier.width(8.dp))
                Caption(m.pill, maxLines = 1)
            }
        }
    }
}

// ---- the sheet

/**
 * The full status of one journey. While it is open, and the app is in front, it asks again every 30 seconds (the service reuses an
 * answer under 25 seconds old); closing it or leaving the app stops that. It stays a bottom sheet.
 */
@Composable
internal fun TrainSheet(trainKey: String, today: TrainsToday, onDismiss: () -> Unit, onEdit: (String) -> Unit = {}) {
    val train = today.booked.firstOrNull { it.key == trainKey }
    if (train == null) {
        LaunchedEffect(trainKey) { onDismiss() }
        return
    }
    val context = LocalContext.current
    val service = context.trainService()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshot = today.snapshot(train)
    val finished by rememberUpdatedState(snapshot.kind.isFinal)

    LaunchedEffect(trainKey, today.liveOn) {
        if (!today.liveOn) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                service.refresh(listOf(train))
                if (finished) break
                delay(SHEET_REFRESH_MS)
            }
        }
    }
    // The "Updated 12 s ago" text moves each second, only while this is showing.
    val clock by com.zyke.message.presentation.home.rememberNowState()
    val now = Instant.ofEpochMilli(clock)
    val failed = trainKey in today.live.failed
    val notFound = trainKey in today.live.notFound
    val refreshing = today.live.loading
    val hasKey by service.hasKey.collectAsStateWithLifecycle()
    val model = TrainCardModel.of(snapshot, now, today.liveOn)
    val stack = rememberStackRoute()

    AdaptiveSheet(
        title = "Train status",
        onDismiss = onDismiss,
        autoPage = false,
        footer = {
            if (!today.liveOn) {
                if (hasKey) AppButton("Turn on live train status", { service.setLiveEnabled(true) }, Modifier.fillMaxWidth())
                else Caption("Live train status needs your own train data key. Add it in Settings, Flights and trains.", Modifier.fillMaxWidth(), maxLines = 3)
            } else {
                AppButton(
                    if (failed) "Retry" else if (refreshing) "Refreshing…" else "Refresh",
                    { scope.launch { service.refresh(listOf(train), manual = true) } },
                    Modifier.fillMaxWidth(), style = if (failed) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE, enabled = !refreshing
                )
            }
        }
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TrainLine(model, tileSize = 52.dp, showPill = !stack, big = true)
                if (stack) TrainPill(model.kind, model.pill)
            }
            TrainRouteBlock(model, big = true, showDuration = true)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.supporting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TrainText.lastSeen(snapshot)?.takeIf { snapshot.isLive && !snapshot.arrived }?.let { Caption(it, maxLines = 3) }
            }
            Facts(snapshot, train, stack, onCopyPnr = { copyPnr(context, it) })
            UpcomingStops(snapshot)
            Freshness(snapshot, today, now, failed, notFound, today.live.reasons[trainKey], trainKey in today.live.paused, hasKey)
            val mine = today.manual.firstOrNull { it.key == trainKey }
            if (mine != null) ManualTrainButtons(mine, onEdit, onDismiss)
        }
    }
}

/** What the sheet knows beyond the route, as tiles in two aligned columns (one column on a narrow phone or at large text). */
@Composable
private fun Facts(s: TrainSnapshot, train: BookedTrain, stack: Boolean, onCopyPnr: (String) -> Unit) {
    val seat = listOfNotNull(train.coach, train.berth).joinToString(" · ").ifEmpty { null }
    val status = listOfNotNull(
        train.ticket?.takeIf { !it.startsWith("CNF") || seat == null }, if (train.chartPrepared) "Chart prepared" else null
    ).joinToString(" · ").ifEmpty { null }
    val facts = listOfNotNull(
        s.platform?.let { "Platform" to it },
        train.trainClass?.let { "Class" to it },
        seat?.let { "Coach · berth" to it },
        status?.let { "Ticket" to it }
    )
    if (facts.isEmpty() && train.pnr == null) {
        Caption("Platform, coach and berth appear here when your messages or the live status give them.", maxLines = 3)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        facts.chunked(if (stack) 1 else 2).forEach { rowFacts ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowFacts.forEach { (label, value) -> FactTile(label, value, Modifier.weight(1f)) }
                if (rowFacts.size == 1 && !stack) Spacer(Modifier.weight(1f))
            }
        }
        train.pnr?.let { pnr ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Caption("PNR")
                    Text(pnr, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The stops still ahead, up to the person's destination, with when each is expected; the first few, the rest behind one button. */
@Composable
private fun UpcomingStops(s: TrainSnapshot) {
    val stops = s.upcoming
    if (!s.isLive || stops.isEmpty()) return
    var all by rememberSaveable { mutableStateOf(false) }
    val delay = s.position?.delayMinutes ?: s.delayMinutes
    val shown = if (all) stops else stops.take(STOPS_COLLAPSED)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            if (stops.size == 1) "Next stop" else "Upcoming stops", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(bottom = 4.dp)
        )
        shown.forEachIndexed { index, stop ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            StopRow(stop, delay, yours = stop.code == s.to?.code)
        }
        if (stops.size > STOPS_COLLAPSED) {
            AppButton(
                if (all) "Show fewer stops" else "Show all ${stops.size} stops", { all = !all },
                Modifier.fillMaxWidth(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
            )
        }
    }
}

@Composable
private fun StopRow(stop: TrainStop, delay: Int, yours: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(TrainText.stopName(stop), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (yours) Caption("Where you get off", maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            TrainText.expectedClock(stop, delay) ?: "—", style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold, color = if (delay >= TrainTracker.LATE_MINUTES) com.zyke.message.presentation.theme.AppColors.warning else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, softWrap = false
        )
    }
}

/** Where the status comes from and how fresh it is; what went wrong when it could not be refreshed; or that it is off. */
@Composable
private fun Freshness(
    s: TrainSnapshot, today: TrainsToday, now: Instant, failed: Boolean, notFound: Boolean, reason: com.zyke.message.domain.diag.LiveFailure? = null,
    paused: Boolean = false, hasKey: Boolean = true
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    when {
        today.demo -> Caption("Demo: a made-up train whose status moves with the clock. Nothing is asked of the internet.", maxLines = 4)
        !today.liveOn -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Live train status is off", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onSurface)
            Caption(
                "These times are from your booking message. " + if (hasKey) "Turning it on asks Live Train Status (Indian Rail API) where the train is, sending only the train number (like ${s.train.number}), the day it started and your own key, never your messages, PNR or stations."
                else "Live train status needs your own train data key, which you add in Settings, Flights and trains. Only the train number (like ${s.train.number}), the day it started and your key are then sent, never your messages, PNR or stations.",
                maxLines = 7
            )
        }
        failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                com.zyke.message.domain.diag.LiveCheck.cardLine(reason, s.updatedAt != null, "train status"),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onSurface
            )
            if (paused) Caption("Not asking again until you save the key again or tomorrow.", maxLines = 2)
            s.updatedAt?.let { Caption(FlightText.updated(now.toEpochMilli(), it)) }
        }
        notFound && s.updatedAt == null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(com.zyke.message.domain.diag.LiveCheck.NO_STATUS_LINE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onSurface)
            Caption("It usually appears when the train starts its run. Showing your booking.", maxLines = 3)
        }
        s.updatedAt != null -> Caption(FlightText.updated(now.toEpochMilli(), s.updatedAt!!) + if (s.kind.isFinal) "" else " · refreshes every 30 s", maxLines = 2)
        else -> Caption("Getting live status…")
    }
}

/** Edit and Remove for a journey added by hand: equal width side by side, stacked on a narrow phone. */
@Composable
private fun ManualTrainButtons(train: com.zyke.message.domain.train.ManualTrain, onEdit: (String) -> Unit, onClose: () -> Unit) {
    val actions = rememberManualTrainActions()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(if (train.confirmed) "Added by you · name and times confirmed" else "Added by you · Schedule not confirmed", maxLines = 2)
        ResponsiveRow(Modifier.fillMaxWidth(), spacing = 12.dp) {
            AppButton("Edit", { onEdit(train.id) }, Modifier.cell(), style = ButtonStyle.OUTLINE)
            AppButton("Remove", { actions.remove(train); onClose() }, Modifier.cell(), style = ButtonStyle.OUTLINE)
        }
    }
}

private fun copyPnr(context: Context, pnr: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PNR", pnr))
    Toast.makeText(context, "PNR copied", Toast.LENGTH_SHORT).show()
}
