package com.zyke.message.presentation.places

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.data.maps.TileSource
import com.zyke.message.domain.places.MapView
import com.zyke.message.domain.places.PlaceCluster
import com.zyke.message.domain.places.PlaceClusters
import com.zyke.message.domain.places.PriceTag
import com.zyke.message.domain.places.SpendHeat
import com.zyke.message.domain.places.SpendPoint
import com.zyke.message.domain.places.TileId
import com.zyke.message.domain.places.TileMath
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppColors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/** Which overlay to draw: by zoom (heat when far, price tags when near), or one of them always. */
internal enum class MapLayer(val label: String) { AUTO("Auto"), HEAT("Heat"), PINS("Prices") }

/** Zoom at which the heat has fully faded to price tags (and where it starts to). */
private const val HEAT_FULL_BELOW = 13.0
private const val HEAT_GONE_AT = 15.0
/** Below this zoom there are too many places in a pixel to draw tags: heat only. */
private const val PINS_FROM = 11.5

/** What the page needs from the map: the hoisted view, its size once measured, and a move between two views that eases unless motion is reduced. */
internal class MapController(val view: MutableState<MapView?>) {
    var width by mutableDoubleStateOf(0.0)
    var height by mutableDoubleStateOf(0.0)

    suspend fun animateTo(to: MapView, spec: androidx.compose.animation.core.FiniteAnimationSpec<Float>) {
        val from = view.value ?: run { view.value = to; return }
        val a = Animatable(0f)
        a.animateTo(1f, spec) {
            val t = value.toDouble()
            view.value = MapView(
                from.centerLat + (to.centerLat - from.centerLat) * t,
                from.centerLon + (to.centerLon - from.centerLon) * t,
                from.zoom + (to.zoom - from.zoom) * t
            )
        }
        view.value = to
    }
}

/** One drawn price tag, kept for finding what a tap landed on. */
private class PinHit(val cluster: PlaceCluster, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The map: tiles (when [tiles] is given), a heat layer and price tags, drawn on one Canvas, with pan, pinch zoom and double-tap
 * zoom. The view is hoisted ([view]) so the page can fit it, centre it and read it for the total line. Nothing here asks the
 * phone or the internet for anything except through [tiles].
 */
@Composable
internal fun SlippyMap(
    points: List<SpendPoint>,
    controller: MapController,
    tiles: TileSource?,
    layer: MapLayer,
    lite: Boolean,
    onCluster: (PlaceCluster) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = controller.view
    val scheme = MaterialTheme.colorScheme
    val hidden = LocalPrivacyMode.current.hidden
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val motion = rememberMotion()
    val scope = rememberCoroutineScope()
    val tileVersion = tiles?.version?.collectAsState()
    val cool = AppColors.info
    val mild = AppColors.success
    val warm = AppColors.warning
    val hot = AppColors.danger
    val placeholder = scheme.surfaceVariant
    val gridLine = scheme.onSurface.copy(alpha = 0.10f)
    val tagFill = scheme.surface
    val tagInk = scheme.onSurface
    val tagBigFill = scheme.onSurface
    val tagBigInk = scheme.surface
    val badge = scheme.primary
    val badgeInk = scheme.onPrimary
    val edge = scheme.onSurface.copy(alpha = 0.18f)
    val dimTiles = scheme.surface.luminance() < 0.5f
    val hits = remember { ArrayList<PinHit>() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }.toDouble()
        val heightPx = with(density) { maxHeight.toPx() }.toDouble()
        LaunchedEffect(widthPx, heightPx) { controller.width = widthPx; controller.height = heightPx }
        val current = view.value ?: MapView(0.0, 0.0, TileMath.MIN_ZOOM.toDouble())

        // Clusters change with the zoom (in quarter steps), not with every pixel of a pan.
        val bucket = (current.zoom * 4).roundToInt() / 4.0
        val clusters = remember(points, bucket) { PlaceClusters.build(points, bucket) }
        val maxTotal = remember(clusters) { clusters.maxOfOrNull { it.total.toDouble() } ?: 1.0 }

        val visibleTiles = if (tiles != null) TileMath.visibleTiles(current, widthPx, heightPx, TileMath.tileZoom(current.zoom)) else emptyList()
        LaunchedEffect(visibleTiles) { tiles?.request(visibleTiles) }

        val heatAlpha = when (layer) {
            MapLayer.HEAT -> 1.0
            MapLayer.PINS -> 0.0
            MapLayer.AUTO -> ((HEAT_GONE_AT - current.zoom) / (HEAT_GONE_AT - HEAT_FULL_BELOW)).coerceIn(0.0, 1.0)
        }
        val showPins = when (layer) { MapLayer.HEAT -> false; MapLayer.PINS -> true; MapLayer.AUTO -> current.zoom >= PINS_FROM }
        val compact = PriceTag.compactAt(current.zoom)
        val spoken = remember(points, clusters.size) { "Map of ${points.size} payments in ${clusters.size} places. Use the list of places for a text version." }

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = spoken }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val v = view.value ?: return@detectTransformGestures
                        val zoomed = if (zoom != 1f) TileMath.zoomAbout(v, zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble(), widthPx, heightPx) else v
                        view.value = TileMath.pan(zoomed, pan.x.toDouble(), pan.y.toDouble())
                    }
                }
                .pointerInput(clusters) {
                    detectTapGestures(
                        onTap = { at -> hits.lastOrNull { at.x in it.left..it.right && at.y in it.top..it.bottom }?.let { onCluster(it.cluster) } },
                        onDoubleTap = { at ->
                            val v = view.value ?: return@detectTapGestures
                            val hit = hits.lastOrNull { at.x in it.left..it.right && at.y in it.top..it.bottom }?.cluster
                            val target = if (hit != null && hit.count > 1) {
                                val z = PlaceClusters.zoomToSplit(hit, v.zoom)
                                MapView(hit.lat, hit.lon, TileMath.clampZoom(z))
                            } else TileMath.zoomAbout(v, 2.0, at.x.toDouble(), at.y.toDouble(), widthPx, heightPx)
                            scope.launch { controller.animateTo(target, motion.tween(260)) }
                        }
                    )
                }
        ) {
            @Suppress("UNUSED_VARIABLE") val redrawOnTile = tileVersion?.value
            val v = current
            drawTiles(v, tiles, placeholder, gridLine, if (dimTiles && tiles != null) scheme.scrim.copy(alpha = 0.28f) else null)

            if (heatAlpha > 0.0) {
                val cells = SpendHeat.cells(points, v, size.width.toDouble(), size.height.toDouble(), lite)
                for (c in cells) {
                    val colour = ramp(c.intensity, cool, mild, warm, hot)
                    val a = (heatAlpha * 0.8).toFloat()
                    val centre = Offset(c.x.toFloat(), c.y.toFloat())
                    if (lite) drawCircle(colour.copy(alpha = a * (0.25f + 0.35f * c.intensity)), c.radiusPx * 0.7f, centre)
                    else drawCircle(
                        Brush.radialGradient(
                            listOf(colour.copy(alpha = a * (0.45f + 0.4f * c.intensity)), colour.copy(alpha = a * 0.3f * c.intensity), colour.copy(alpha = 0f)),
                            center = centre, radius = c.radiusPx
                        ),
                        c.radiusPx, centre
                    )
                }
            }

            hits.clear()
            if (showPins) {
                val pinAlpha = if (layer == MapLayer.AUTO) ((v.zoom - PINS_FROM) / 1.0).coerceIn(0.0, 1.0).toFloat() else 1f
                // Small spenders first, so the big tags are drawn last and stay readable.
                val onScreen = clusters.asSequence().mapNotNull { c ->
                    val (x, y) = TileMath.toScreen(c.lat, c.lon, v, size.width.toDouble(), size.height.toDouble())
                    if (x < -80 || x > size.width + 80 || y < -60 || y > size.height + 60) null else Triple(c, x.toFloat(), y.toFloat())
                }.sortedBy { it.first.total }.toList().takeLast(MAX_TAGS)
                for ((c, x, y) in onScreen) {
                    val rel = sqrt(c.total.toDouble() / max(maxTotal, 1.0)).toFloat().coerceIn(0f, 1f)
                    val big = rel >= 0.8f
                    drawTag(
                        measurer, PriceTag.label(c.total, compact, hidden), c.count, x, y, rel, big,
                        fill = if (big) tagBigFill else tagFill, ink = if (big) tagBigInk else tagInk, edge = edge,
                        badge = badge, badgeInk = badgeInk, alpha = pinAlpha, hits = hits, cluster = c
                    )
                }
            }
        }
    }
}

private const val MAX_TAGS = 150

private fun ramp(t: Float, cool: Color, mild: Color, warm: Color, hot: Color): Color {
    val colours = listOf(cool, mild, warm, hot)
    val x = t.coerceIn(0f, 1f)
    // Four colours over the stops 0, .33, .66, 1 of the 0..1 range.
    val seg = (x * (colours.size - 1)).coerceAtMost(colours.size - 1.001f)
    val i = seg.toInt()
    return lerp(colours[i], colours[i + 1], seg - i)
}

/** Draws every square that is on screen: the stored picture, else a blurrier one from a lower zoom, else a blank with a faint outline. */
internal fun DrawScope.drawTiles(view: MapView, tiles: TileSource?, placeholder: Color, gridLine: Color, scrim: Color?) {
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    drawRect(placeholder)
    val z = TileMath.tileZoom(view.zoom)
    val scale = 2.0.pow(view.zoom - z)
    val cx = TileMath.lonToX(view.centerLon, z.toDouble())
    val cy = TileMath.latToY(view.centerLat, z.toDouble())
    val side = (TileMath.TILE * scale).toFloat()
    val sideInt = ceil(side).toInt() + 1
    for (t in TileMath.visibleTiles(view, w, h, z)) {
        val left = ((t.x * TileMath.TILE - cx) * scale + w / 2).toFloat()
        val top = ((t.y * TileMath.TILE - cy) * scale + h / 2).toFloat()
        val dst = IntOffset(left.roundToInt(), top.roundToInt())
        val bitmap = tiles?.bitmap(t)
        if (bitmap != null) {
            drawImage(bitmap.asImageBitmap(), dstOffset = dst, dstSize = IntSize(sideInt, sideInt))
        } else {
            var drawn = false
            if (tiles != null) {
                for (k in 1..3) {
                    val pz = t.z - k
                    if (pz < TileMath.MIN_ZOOM) break
                    val parent = tiles.bitmap(TileId(pz, t.x shr k, t.y shr k)) ?: continue
                    val sub = TileMath.TILE shr k
                    val sx = (t.x - ((t.x shr k) shl k)) * sub
                    val sy = (t.y - ((t.y shr k) shl k)) * sub
                    drawImage(parent.asImageBitmap(), srcOffset = IntOffset(sx, sy), srcSize = IntSize(sub, sub), dstOffset = dst, dstSize = IntSize(sideInt, sideInt))
                    drawn = true
                    break
                }
            }
            if (!drawn) drawRect(gridLine, topLeft = Offset(left, top), size = Size(side, side), style = Stroke(width = 1f))
        }
    }
    if (scrim != null) drawRect(scrim)
}

/** One price tag pointing at ([x], [y]): a rounded label above the spot with a small point, and a count badge when it holds several payments. */
private fun DrawScope.drawTag(
    measurer: TextMeasurer, label: String, count: Int, x: Float, y: Float, rel: Float, big: Boolean,
    fill: Color, ink: Color, edge: Color, badge: Color, badgeInk: Color, alpha: Float,
    hits: MutableList<PinHit>, cluster: PlaceCluster
) {
    val style = TextStyle(fontSize = (12f + 4f * rel).sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = alpha))
    val text = measurer.measure(label, style)
    val padX = 9.dp.toPx()
    val padY = 5.dp.toPx()
    val w = text.size.width + padX * 2
    val h = text.size.height + padY * 2
    val point = 6.dp.toPx()
    val left = x - w / 2
    val top = y - point - h
    val radius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx(), 12.dp.toPx())
    drawRoundRect(fill.copy(alpha = alpha), Offset(left, top), Size(w, h), radius)
    drawRoundRect(edge.copy(alpha = edge.alpha * alpha), Offset(left, top), Size(w, h), radius, style = Stroke(1.dp.toPx()))
    val tail = Path().apply {
        moveTo(x - point, top + h - 1f); lineTo(x + point, top + h - 1f); lineTo(x, y); close()
    }
    drawPath(tail, fill.copy(alpha = alpha))
    drawText(text, topLeft = Offset(left + padX, top + padY))
    if (count > 1) {
        val r = 9.dp.toPx()
        val c = Offset(left + w - 2.dp.toPx(), top + 2.dp.toPx())
        drawCircle(badge.copy(alpha = alpha), r, c)
        val n = measurer.measure(if (count > 99) "99+" else count.toString(), TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = badgeInk.copy(alpha = alpha)))
        drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - n.size.height / 2f))
    }
    hits.add(PinHit(cluster, left - 4.dp.toPx(), top - 8.dp.toPx(), left + w + 4.dp.toPx(), y + 6.dp.toPx()))
}
