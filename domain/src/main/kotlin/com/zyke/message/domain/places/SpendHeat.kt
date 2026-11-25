package com.zyke.message.domain.places

import kotlin.math.max
import kotlin.math.sqrt

/**
 * One soft blob of the heat layer: its middle in screen pixels, [intensity] 0..1 (the busiest cell is 1) and how much was
 * spent in it. [radiusPx] is how far it spreads.
 */
data class HeatCell(val x: Double, val y: Double, val intensity: Float, val amount: Double, val radiusPx: Float)

object SpendHeat {
    /** Side of a grid cell in screen pixels at any zoom: the grid is fixed in the world at each zoom, so panning never changes a blob. */
    const val CELL_PX = 28.0

    /**
     * Sums [points] into a coarse grid fixed to the world at the view's zoom and returns the cells that touch the screen.
     * Intensity grows with the square root of the cell's total, so one big purchase does not wash every other cell out.
     * The blob is drawn at the weighted middle of its cell's payments, not the cell's corner. [lite] makes the grid twice as
     * coarse (fewer, simpler blobs).
     */
    fun cells(points: List<SpendPoint>, view: MapView, width: Double, height: Double, lite: Boolean = false): List<HeatCell> {
        if (points.isEmpty() || width <= 0 || height <= 0) return emptyList()
        val cell = if (lite) CELL_PX * 2 else CELL_PX
        val cx = TileMath.lonToX(view.centerLon, view.zoom)
        val cy = TileMath.latToY(view.centerLat, view.zoom)
        class Acc(var sum: Double = 0.0, var sx: Double = 0.0, var sy: Double = 0.0)
        val grid = HashMap<Long, Acc>()
        val margin = cell * 2
        for (p in points) {
            val wx = TileMath.lonToX(p.lon, view.zoom)
            val wy = TileMath.latToY(p.lat, view.zoom)
            val sx = wx - cx + width / 2.0
            val sy = wy - cy + height / 2.0
            if (sx < -margin || sx > width + margin || sy < -margin || sy > height + margin) continue
            val a = p.amount.toDouble()
            val key = Math.floorDiv(wx.toLong(), cell.toLong()) * 4_000_037L + Math.floorDiv(wy.toLong(), cell.toLong())
            val acc = grid.getOrPut(key) { Acc() }
            acc.sum += a; acc.sx += sx * a; acc.sy += sy * a
        }
        if (grid.isEmpty()) return emptyList()
        val top = sqrt(grid.values.maxOf { it.sum }).let { max(it, 1e-9) }
        val radius = (cell * if (lite) 1.6 else 2.2).toFloat()
        return grid.values.map { a ->
            HeatCell(a.sx / a.sum, a.sy / a.sum, (sqrt(a.sum) / top).toFloat().coerceIn(0.05f, 1f), a.sum, radius)
        }.sortedBy { it.intensity }
    }

    /** Blob colour stops from cool to warm; the app maps these positions to theme colours. */
    val RAMP_STOPS = floatArrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
}
