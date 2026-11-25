package com.zyke.message.presentation.places

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.maps.TileSource
import com.zyke.message.domain.categories.CategoryOptions
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.places.MapView
import com.zyke.message.domain.places.PlaceCluster
import com.zyke.message.domain.places.PlaceClusters
import com.zyke.message.domain.places.PlacePeriod
import com.zyke.message.domain.places.SpendPlace
import com.zyke.message.domain.places.SpendPoint
import com.zyke.message.domain.places.SpendPoints
import com.zyke.message.domain.places.TileMath
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LocalLite
import com.zyke.message.presentation.common.LocalOpenMessage
import com.zyke.message.presentation.common.LocalOpenSettings
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

private val ViewSaver = Saver<MutableState<MapView?>, List<Double>>(
    save = { s -> s.value?.let { listOf(it.centerLat, it.centerLon, it.zoom) } ?: emptyList() },
    restore = { l -> mutableStateOf(if (l.size == 3) MapView(l[0], l[1], l[2]) else null) }
)

/**
 * "Where you spend": a map of the places payments were made, with a heat layer when zoomed out and price tags when zoomed in.
 * It is a full page; the map takes every touch inside it. Needs no internet (pins and heat are drawn from what is on the phone);
 * the map pictures behind them come from OpenStreetMap only if the person turned them on, and never in the demo.
 */
@Composable
internal fun SpendMapPage(request: MapRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val demo = com.zyke.message.data.demo.DemoMode.active
    val placeState by container.spendPlaces.state.collectAsState()
    val settings = placeState.settings
    val lite = LocalLite.current
    val motion = rememberMotion()
    val scope = rememberCoroutineScope()
    val openSettings = LocalOpenSettings.current
    val openMessage = LocalOpenMessage.current

    val messages by produceState<List<ClassifiedMessage>>(emptyList(), container) {
        container.observeMessages.calm(2_000L).collect { value = it }
    }
    val allPoints by produceState<List<SpendPoint>>(emptyList(), messages, placeState.places) {
        value = withContext(Dispatchers.Default) { SpendPoints.build(messages, placeState.places) }
    }

    var periodName by rememberSaveable { mutableStateOf(PlacePeriod.THREE_MONTHS.name) }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var layerName by rememberSaveable { mutableStateOf(MapLayer.AUTO.name) }
    val period = PlacePeriod.valueOf(periodName)
    val category = categoryName?.let { runCatching { MessageCategory.valueOf(it) }.getOrNull() }
    val layer = MapLayer.valueOf(layerName)
    val points = remember(allPoints, period, category) { SpendPoints.filter(allPoints, period, category, System.currentTimeMillis(), ZoneId.systemDefault()) }
    val categories = remember(allPoints) {
        val present = allPoints.map { it.category }.toSet()
        CategoryOptions.forSpending().filter { it in present }
    }

    val view = rememberSaveable(saver = ViewSaver) { mutableStateOf<MapView?>(null) }
    val controller = remember { MapController(view) }
    var fitted by rememberSaveable { mutableStateOf(false) }
    val showPictures = settings.mapPictures && !demo
    val tiles = remember(showPictures) { if (showPictures) TileSource(context.cacheDir, offline = false) else null }
    DisposableEffect(tiles) { onDispose { tiles?.close() } }

    // The first view: on the place asked for, else wherever the payments are.
    LaunchedEffect(controller.width, controller.height, allPoints.isNotEmpty()) {
        if (!fitted && controller.width > 0 && controller.height > 0) {
            val lat = request.lat
            val lon = request.lon
            if (lat != null && lon != null) { view.value = MapView(lat, lon, request.zoom ?: 16.0); fitted = true }
            else if (points.isNotEmpty()) {
                view.value = TileMath.fit(points.map { it.lat to it.lon }, controller.width, controller.height, 64.0)
                fitted = true
            }
        }
    }

    var inView by remember { mutableStateOf(SpendPoints.InView(java.math.BigDecimal.ZERO, 0)) }
    LaunchedEffect(points, controller.width, controller.height) {
        snapshotFlow { view.value }.collectLatest { v ->
            delay(100)
            if (v != null && controller.width > 0) inView = withContext(Dispatchers.Default) { SpendPoints.inView(points, v, controller.width, controller.height) }
        }
    }

    var selected by remember { mutableStateOf<PlaceCluster?>(null) }
    var listing by remember { mutableStateOf(false) }
    var picturesAsk by remember { mutableStateOf(false) }

    fun zoomBy(factor: Double) {
        val v = view.value ?: return
        val target = TileMath.zoomAbout(v, factor, controller.width / 2, controller.height / 2, controller.width, controller.height)
        scope.launch { controller.animateTo(target, motion.tween(220)) }
    }
    fun myArea() {
        val target = TileMath.fit(points.map { it.lat to it.lon }, controller.width, controller.height, 64.0) ?: return
        scope.launch { controller.animateTo(target, motion.tween(260)) }
    }

    AppPage("Where you spend", onDismiss, scrollable = false) {
        if (allPoints.isEmpty()) {
            Column(Modifier.padding(horizontal = Spacing.lg)) {
                EmptyState(
                    title = if (settings.enabled) "No places yet" else "Nothing on the map yet",
                    body = if (settings.enabled) "A place is saved when a purchase text arrives while this is on. Your next shop payment will appear here."
                    else "Turn on \"Remember where I spend\" and each purchase text saves where you were, only on this phone.",
                    actionLabel = if (settings.enabled) null else "Open settings",
                    onAction = if (settings.enabled) null else ({ onDismiss(); com.zyke.message.presentation.settings.SettingsDeepLink.pending = "PLACES"; openSettings() })
                )
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SlippyMap(points, controller, tiles, layer, lite, onCluster = { selected = it }, modifier = Modifier.fillMaxSize())

                Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        PlacePeriod.entries.forEach { p -> MapChip(p.label, p == period) { periodName = p.name } }
                    }
                    if (categories.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            MapChip("All", category == null) { categoryName = null }
                            categories.forEach { c -> MapChip(c.displayString, c == category) { categoryName = c.name } }
                        }
                    }
                }

                Column(Modifier.align(Alignment.CenterEnd).padding(end = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MapButton("+", "Zoom in") { zoomBy(2.0) }
                    MapButton("−", "Zoom out") { zoomBy(0.5) }
                    MapButton("◎", "My area, fit all places") { myArea() }
                    MapButton(layer.label, "Layer: ${layer.label}. Tap to change", wide = true) { layerName = MapLayer.entries[(layer.ordinal + 1) % MapLayer.entries.size].name }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        when {
                            demo -> "Map pictures are off in demo"
                            tiles != null -> "© OpenStreetMap contributors"
                            else -> "Map pictures are off"
                        },
                        Modifier.align(Alignment.End).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp)
                            .then(if (!demo && tiles == null) Modifier.tappable({ picturesAsk = true }, role = Role.Button, onClickLabel = "Turn on map pictures") else Modifier),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.control)).background(MaterialTheme.colorScheme.surface)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)), RoundedCornerShape(Radius.control))
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            if (inView.count == 0) Text("No payments in view", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            else Row(verticalAlignment = Alignment.Bottom) {
                                PrivateAmount(DisplayFormatters.rupees(inView.total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    " across ${DisplayFormatters.count(inView.count, "payment", "payments")} in view",
                                    Modifier.padding(start = Spacing.xs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        AppButton("Places", { listing = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                    }
                }
            }
        }
    }

    selected?.let { cluster ->
        ClusterSheet(
            cluster = cluster, names = placeState.names, geocode = settings.geocodeNames,
            onDismiss = { selected = null },
            onOpenMessage = { id -> selected = null; onDismiss(); openMessage(id) },
            onZoom = {
                selected = null
                val v = view.value
                val z = TileMath.clampZoom(if (v != null) PlaceClusters.zoomToSplit(cluster, v.zoom).coerceAtLeast(v.zoom + 2.0) else 16.0)
                scope.launch { controller.animateTo(MapView(cluster.lat, cluster.lon, z), motion.tween(260)) }
            },
            onName = { spot, name -> container.spendPlaces.setNickname(spot, name) }
        )
    }
    if (listing) {
        val v = view.value
        PlacesListSheet(
            points = points, view = v, width = controller.width, height = controller.height, names = placeState.names,
            onDismiss = { listing = false }, onPick = { listing = false; selected = it }
        )
    }
    if (picturesAsk) MapPicturesSheet(onDismiss = { picturesAsk = false }, onTurnOn = { container.spendPlaces.update { s -> s.copy(mapPictures = true) }; picturesAsk = false })
}

/** The words that ask for map pictures: the only thing a map square request reveals is which part of the world is on screen. */
@Composable
internal fun MapPicturesSheet(onDismiss: () -> Unit, onTurnOn: () -> Unit) {
    AdaptiveSheet("Map pictures", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Map pictures come from OpenStreetMap; only the tile coordinates being viewed are requested.", style = MaterialTheme.typography.bodyLarge)
            Caption("Your places, amounts and messages are never sent. OpenStreetMap can see which part of the world a square is for, and your phone's address, as with any map. Squares are kept on this phone for a month so the map works offline. You can turn this off in Settings, Spending places.", maxLines = 8)
            AppButton("Turn on map pictures", onTurnOn, Modifier.fillMaxWidth())
            AppButton("Not now", onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
        }
    }
}

@Composable
private fun MapChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(Radius.chip))
            .background(if (selected) scheme.primary else scheme.surface)
            .border(BorderStroke(1.dp, scheme.onSurface.copy(alpha = if (selected) 0f else 0.14f)), RoundedCornerShape(Radius.chip))
            .semantics { this.selected = selected }
            .tappable(onClick, role = Role.RadioButton, tick = true)
            .padding(horizontal = Spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) scheme.onPrimary else scheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun MapButton(glyph: String, description: String, wide: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(Radius.chip)).background(scheme.surface)
            .border(BorderStroke(1.dp, scheme.onSurface.copy(alpha = 0.14f)), RoundedCornerShape(Radius.chip))
            .semantics { contentDescription = description }
            .tappable(onClick, role = Role.Button, tick = true)
            .padding(horizontal = if (wide) Spacing.sm else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, style = if (wide) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1)
    }
}

/** The nickname for a spot: the person's, found under any of the payments there (the biggest first). */
private fun nameOf(cluster: PlaceCluster, names: Map<String, String>): String? =
    cluster.members.sortedByDescending { it.amount }.firstNotNullOfOrNull { names[SpendPlace.spotKey(it.lat, it.lon)] }

/** What was paid at one price tag: the total, the payments (each opens its message), a name for the place, and a way to zoom in. */
@Composable
private fun ClusterSheet(
    cluster: PlaceCluster, names: Map<String, String>, geocode: Boolean,
    onDismiss: () -> Unit, onOpenMessage: (Long) -> Unit, onZoom: () -> Unit, onName: (String, String?) -> Unit
) {
    val nickname = nameOf(cluster, names)
    val geocoded = rememberPlaceName(cluster.lat, cluster.lon, geocode && nickname == null)
    val title = nickname ?: geocoded ?: coordinatesText(cluster.lat, cluster.lon)
    var naming by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(nickname.orEmpty()) }
    AdaptiveSheet(title, onDismiss, subtitle = DisplayFormatters.count(cluster.count, "payment", "payments")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Total", Modifier.heading(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
                PrivateAmount(DisplayFormatters.rupees(cluster.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            cluster.members.take(MAX_ROWS).forEach { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).tappable({ onOpenMessage(p.messageId) }, role = Role.Button, onClickLabel = "Open message"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.merchant.ifBlank { "Payment" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Caption(DisplayFormatters.listStamp(p.date) + if (p.rough) " · approximate place" else "")
                    }
                    PrivateAmount(DisplayFormatters.rupees(p.amount), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (cluster.count > MAX_ROWS) Caption("and ${cluster.count - MAX_ROWS} more, the oldest")
            AppButton(if (nickname == null) "Name this place" else "Rename this place", { draft = nickname.orEmpty(); naming = true }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            if (cluster.count > 1) AppButton("Zoom in", onZoom, Modifier.fillMaxWidth())
        }
    }
    if (naming) {
        val anchor = cluster.members.maxByOrNull { it.amount }
        FormSheet(
            title = "Name this place", subtitle = "Only on this phone", onDismiss = { naming = false },
            primaryLabel = "Save", primaryEnabled = anchor != null,
            onPrimary = { if (anchor != null) onName(SpendPlace.spotKey(anchor.lat, anchor.lon), draft); naming = false },
            secondaryLabel = if (nickname != null) "Remove name" else null,
            onSecondary = if (nickname != null && anchor != null) ({ onName(SpendPlace.spotKey(anchor.lat, anchor.lon), null); naming = false }) else null
        ) {
            FormField("Name", draft, { draft = it.take(40) }, placeholder = "Corner shop", autoFocus = true)
        }
    }
}

private const val MAX_ROWS = 30

/** The places in view as a list, biggest spend first: the same information as the tags, for anyone who cannot use the map by touch. */
@Composable
private fun PlacesListSheet(
    points: List<SpendPoint>, view: MapView?, width: Double, height: Double, names: Map<String, String>,
    onDismiss: () -> Unit, onPick: (PlaceCluster) -> Unit
) {
    val clusters = remember(points, view, width, height) {
        val v = view
        val inside = if (v == null) points else points.filter { p -> TileMath.toScreen(p.lat, p.lon, v, width, height).let { (x, y) -> x in 0.0..width && y in 0.0..height } }
        PlaceClusters.build(inside, v?.zoom ?: 12.0).sortedByDescending { it.total }.take(40)
    }
    AdaptiveSheet("Places in view", onDismiss) {
        if (clusters.isEmpty()) Caption("No payments in view", maxLines = 2)
        Column {
            clusters.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).tappable({ onPick(c) }, role = Role.Button, onClickLabel = "Open place"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(nameOf(c, names) ?: c.members.maxByOrNull { it.amount }?.merchant?.ifBlank { null } ?: coordinatesText(c.lat, c.lon), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Caption(DisplayFormatters.count(c.count, "payment", "payments"))
                    }
                    PrivateAmount(DisplayFormatters.rupees(c.total), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
