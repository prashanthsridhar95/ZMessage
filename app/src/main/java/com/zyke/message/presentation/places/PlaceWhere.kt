package com.zyke.message.presentation.places

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.maps.TileSource
import com.zyke.message.data.places.PlaceNamer
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.categories.PayeeKey
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.places.CaptureDecision
import com.zyke.message.domain.places.PlaceCapture
import com.zyke.message.domain.places.MapView
import com.zyke.message.domain.places.PlaceAccuracy
import com.zyke.message.domain.places.SpendPlace
import com.zyke.message.domain.places.SpendPoints
import com.zyke.message.domain.places.TileMath
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.theme.AppColors
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.launch

/** "12.9716° N, 77.5946° E": what is shown when no name is available or names are off. */
internal fun coordinatesText(lat: Double, lon: Double): String =
    "%.4f° %s, %.4f° %s".format(java.util.Locale.ROOT, kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

/** The name of a place: the person's own nickname for the spot, else the geocoder's (if allowed), else null while loading or unavailable. */
@Composable
internal fun rememberPlaceName(lat: Double, lon: Double, geocode: Boolean): String? {
    val context = LocalContext.current
    val name by produceState<String?>(null, lat, lon, geocode) {
        value = when {
            com.zyke.message.data.demo.DemoMode.active -> "A made-up place (demo)"
            geocode -> PlaceNamer.name(context, lat, lon)
            else -> null
        }
    }
    return name
}

/**
 * "Where" in a payment's details, like the place of a photo: a small map with a pin, the name of the place (or its coordinates),
 * and Open map / Remove location. Shown only for a payment that has a stored place; removing it can be undone.
 */
@Composable
internal fun SpendPlaceSection(item: ClassifiedMessage) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val state by container.spendPlaces.state.collectAsState()
    val place = remember(item.message.sender, item.message.body, item.message.date, state.places) { SpendPoints.placeOf(item, state.places) }
    val tx = item.content as? MessageContent.Transaction
    val payeeKey = tx?.payee?.let { PayeeKey.of(it) }
    if (place != null) Where(place, state.names[SpendPlace.spotKey(place.lat, place.lon)], state.settings.geocodeNames, state.settings.mapPictures, item.message.date, payeeKey, tx?.payee.orEmpty())
    else if (state.settings.enabled && payeeKey != null && !com.zyke.message.data.demo.DemoMode.active &&
        PlaceCapture.decide(item, state.settings) == CaptureDecision.Skip("online payee")
    ) {
        // The rule skipped it as an online payee; the person can say this one is a shop (it applies to payments that arrive from now on).
        FintechCard {
            Text("Where", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.heading())
            Caption("No place saved: ${tx?.payee.orEmpty().ifBlank { "this payee" }} looks like an online shop, where you were does not say much.", Modifier.padding(top = Spacing.xs), maxLines = 3)
            AppButton(
                "Save places for this payee from now on",
                { container.spendPlaces.update { it.copy(captureOverrides = it.captureOverrides + payeeKey, skipOverrides = it.skipOverrides - payeeKey) } },
                Modifier.fillMaxWidth().padding(top = Spacing.md), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
            )
        }
    }
}

@Composable
private fun Where(place: SpendPlace, nickname: String?, geocode: Boolean, pictures: Boolean, paidAt: Long, payeeKey: String?, payee: String) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val name = nickname ?: rememberPlaceName(place.lat, place.lon, geocode) ?: coordinatesText(place.lat, place.lon)
    val open = { SpendMapRequests.show(place.lat, place.lon, 17.0) }
    FintechCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Where", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.heading())
            if (place.accuracy == PlaceAccuracy.ROUGH) Caption("Approximate")
        }
        Box(Modifier.padding(top = Spacing.md).fillMaxWidth().aspectRatio(2.2f).clip(RoundedCornerShape(Spacing.lg))) {
            MapThumbnail(place, pictures, Modifier.fillMaxSize().tappable(open, role = Role.Button, onClickLabel = "Open map"))
        }
        Text(name, Modifier.padding(top = Spacing.md), style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2)
        Caption("Saved on this phone when the text arrived · ${DisplayFormatters.time(place.at.takeIf { it > 0 } ?: paidAt)}", Modifier.padding(top = Spacing.xs), maxLines = 2)
        ResponsiveRow(Modifier.fillMaxWidth().padding(top = Spacing.md), spacing = Spacing.sm) {
            AppButton("Open map", open, Modifier.cell(), size = ButtonSize.COMPACT)
            AppButton(
                "Remove location",
                {
                    haptics.tick()
                    val removed = container.spendPlaces.remove(place.key)
                    if (removed != null) scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        val result = snackbar.showSnackbar("Location removed", actionLabel = "Undo", duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) container.spendPlaces.restore(removed)
                    }
                },
                Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
            )
        }
        if (payeeKey != null) {
            AppButton(
                "Don't save places for ${payee.ifBlank { "this payee" }}",
                { container.spendPlaces.update { it.copy(skipOverrides = it.skipOverrides + payeeKey, captureOverrides = it.captureOverrides - payeeKey) } },
                Modifier.fillMaxWidth(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
            )
        }
    }
}

/**
 * A still map of one place with a pin. Map pictures only when the person allowed them (and never in the demo); otherwise a
 * blank grid, so opening a payment never asks the internet for anything on its own.
 */
@Composable
internal fun MapThumbnail(place: SpendPlace, pictures: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val demo = com.zyke.message.data.demo.DemoMode.active
    val tiles = remember(pictures, demo) { if (pictures && !demo) TileSource(context.cacheDir, offline = false) else null }
    DisposableEffect(tiles) { onDispose { tiles?.close() } }
    val version = tiles?.version?.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val pin = AppColors.danger
    val pinHole = scheme.surface
    val placeholder = scheme.surfaceVariant
    val grid = scheme.onSurface.copy(alpha = 0.10f)
    val density = LocalDensity.current
    val view = remember(place) { MapView(place.lat, place.lon, if (place.accuracy == PlaceAccuracy.ROUGH) 15.0 else 16.0) }
    BoxWithConstraints(modifier.semantics { contentDescription = "Map of where this payment was made" }) {
        val w = with(density) { maxWidth.toPx() }.toDouble()
        val h = with(density) { maxHeight.toPx() }.toDouble()
        val wanted = if (tiles != null && w > 0) TileMath.visibleTiles(view, w, h, TileMath.tileZoom(view.zoom)) else emptyList()
        LaunchedEffect(wanted) { tiles?.request(wanted) }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val redraw = version?.value
            drawTiles(view, tiles, placeholder, grid, null)
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = 9.dp.toPx()
            val tail = Path().apply { moveTo(c.x - r * 0.8f, c.y - r * 1.2f); lineTo(c.x + r * 0.8f, c.y - r * 1.2f); lineTo(c.x, c.y); close() }
            drawPath(tail, pin)
            drawCircle(pin, r, Offset(c.x, c.y - r * 2f))
            drawCircle(pinHole, r * 0.4f, Offset(c.x, c.y - r * 2f))
        }
        Text(
            when {
                demo -> "Map pictures are off in demo"
                tiles != null -> "© OpenStreetMap contributors"
                else -> "Map pictures are off"
            },
            Modifier.align(Alignment.BottomEnd).padding(Spacing.xs).background(scheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = scheme.onSurface
        )
    }
}
