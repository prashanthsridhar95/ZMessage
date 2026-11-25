package com.zyke.message.domain.places

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** A point on the map as the person sees it: the middle of the screen and how far in it is (fractional zoom is fine). */
data class MapView(val centerLat: Double, val centerLon: Double, val zoom: Double)

/** One map tile of the usual 256-pixel slippy-map grid. */
data class TileId(val z: Int, val x: Int, val y: Int)

/**
 * Web-Mercator maths for a slippy map, pure so it can be tested. World pixels at zoom z run 0 until 256 * 2^z on both
 * axes, x from the west edge, y from the north edge (the same convention OpenStreetMap's tiles use).
 */
object TileMath {
    const val TILE = 256
    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 19
    /** Latitudes beyond this do not exist in Web Mercator. */
    const val MAX_LAT = 85.05112878

    fun clampZoom(zoom: Double): Double = zoom.coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())

    fun worldSize(zoom: Double): Double = TILE * 2.0.pow(zoom)

    fun lonToX(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    fun latToY(lat: Double, zoom: Double): Double {
        val l = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(l) + 1.0 / cos(l)) / PI) / 2.0 * worldSize(zoom)
    }

    fun xToLon(x: Double, zoom: Double): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun yToLat(y: Double, zoom: Double): Double {
        val n = PI - 2.0 * PI * y / worldSize(zoom)
        return atan(sinh(n)) * 180.0 / PI
    }

    /** The tile that holds this point at an integer zoom. */
    fun tileOf(lat: Double, lon: Double, z: Int): TileId {
        val n = 1 shl z
        val x = floor(lonToX(lon, z.toDouble()) / TILE).toInt().coerceIn(0, n - 1)
        val y = floor(latToY(lat, z.toDouble()) / TILE).toInt().coerceIn(0, n - 1)
        return TileId(z, x, y)
    }

    /** Where a coordinate falls on a screen of [width] x [height] pixels showing [view]; (0,0) is the top-left corner. */
    fun toScreen(lat: Double, lon: Double, view: MapView, width: Double, height: Double): Pair<Double, Double> {
        val cx = lonToX(view.centerLon, view.zoom)
        val cy = latToY(view.centerLat, view.zoom)
        return (lonToX(lon, view.zoom) - cx + width / 2.0) to (latToY(lat, view.zoom) - cy + height / 2.0)
    }

    /** The coordinate under a screen point. */
    fun fromScreen(px: Double, py: Double, view: MapView, width: Double, height: Double): Pair<Double, Double> {
        val cx = lonToX(view.centerLon, view.zoom)
        val cy = latToY(view.centerLat, view.zoom)
        return yToLat(cy + py - height / 2.0, view.zoom) to xToLon(cx + px - width / 2.0, view.zoom)
    }

    /** Moves the view by a drag of ([dx], [dy]) screen pixels (the map follows the finger). */
    fun pan(view: MapView, dx: Double, dy: Double): MapView {
        val size = worldSize(view.zoom)
        val x = (lonToX(view.centerLon, view.zoom) - dx)
        val y = (latToY(view.centerLat, view.zoom) - dy).coerceIn(0.0, size)
        return view.copy(centerLat = yToLat(y, view.zoom), centerLon = wrapLon(xToLon(x, view.zoom)))
    }

    /** Zooms by [factor] (2.0 is one level in) keeping the coordinate under ([px], [py]) where it is. */
    fun zoomAbout(view: MapView, factor: Double, px: Double, py: Double, width: Double, height: Double): MapView {
        val newZoom = clampZoom(view.zoom + ln(factor) / ln(2.0))
        if (newZoom == view.zoom) return view
        val (lat, lon) = fromScreen(px, py, view, width, height)
        val x = lonToX(lon, newZoom) - (px - width / 2.0)
        val y = latToY(lat, newZoom) - (py - height / 2.0)
        return MapView(yToLat(y.coerceIn(0.0, worldSize(newZoom)), newZoom), wrapLon(xToLon(x, newZoom)), newZoom)
    }

    private fun wrapLon(lon: Double): Double {
        var l = lon
        while (l > 180.0) l -= 360.0
        while (l < -180.0) l += 360.0
        return l
    }

    /**
     * The view that shows every point with [padding] pixels spare on each side, as far in as that allows (at most
     * [maxZoom], so a single place does not zoom to the roof). No points: null.
     */
    fun fit(points: List<Pair<Double, Double>>, width: Double, height: Double, padding: Double = 48.0, maxZoom: Int = 17): MapView? {
        if (points.isEmpty() || width <= 0 || height <= 0) return null
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        val availW = max(1.0, width - 2 * padding)
        val availH = max(1.0, height - 2 * padding)
        var best = MIN_ZOOM.toDouble()
        var z = min(maxZoom, MAX_ZOOM).toDouble()
        while (z >= MIN_ZOOM) {
            val w = lonToX(maxLon, z) - lonToX(minLon, z)
            val h = latToY(minLat, z) - latToY(maxLat, z)
            if (w <= availW && h <= availH) { best = z; break }
            z -= 0.25
        }
        val cx = (lonToX(minLon, best) + lonToX(maxLon, best)) / 2.0
        val cy = (latToY(minLat, best) + latToY(maxLat, best)) / 2.0
        return MapView(yToLat(cy, best), xToLon(cx, best), best)
    }

    /**
     * The tiles needed to cover a screen. Tiles are taken from the integer zoom [z] (drawn scaled by 2^(zoom - z)); the
     * list is at most a screenful, sorted nearest the middle first so the tiles under the person's eyes load first.
     */
    fun visibleTiles(view: MapView, width: Double, height: Double, z: Int): List<TileId> {
        val scale = 2.0.pow(view.zoom - z)
        val cx = lonToX(view.centerLon, z.toDouble())
        val cy = latToY(view.centerLat, z.toDouble())
        val halfW = width / 2.0 / scale
        val halfH = height / 2.0 / scale
        val n = 1 shl z
        val x0 = floor((cx - halfW) / TILE).toInt()
        val x1 = floor((cx + halfW) / TILE).toInt()
        val y0 = max(0, floor((cy - halfH) / TILE).toInt())
        val y1 = min(n - 1, floor((cy + halfH) / TILE).toInt())
        val tiles = ArrayList<TileId>()
        for (ty in y0..y1) for (tx in x0..x1) tiles.add(TileId(z, ((tx % n) + n) % n, ty))
        return tiles.sortedBy { t ->
            val dx = (t.x + 0.5) * TILE - cx
            val dy = (t.y + 0.5) * TILE - cy
            dx * dx + dy * dy
        }
    }

    /** The integer zoom whose tiles are the sharpest that are not drawn smaller than they were made. */
    fun tileZoom(zoom: Double): Int = floor(zoom + 0.0001).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Metres per pixel at a latitude and zoom, for a scale bar. */
    fun metersPerPixel(lat: Double, zoom: Double): Double = 156543.03392 * cos(lat * PI / 180.0) / 2.0.pow(zoom)

}
