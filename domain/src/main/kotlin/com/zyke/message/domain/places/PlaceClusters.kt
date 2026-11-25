package com.zyke.message.domain.places

import java.math.BigDecimal

/** Payments close together on screen, drawn as one price tag. [lat]/[lon] is the amount-weighted middle. */
data class PlaceCluster(
    val lat: Double,
    val lon: Double,
    val total: BigDecimal,
    val count: Int,
    val members: List<SpendPoint>
)

object PlaceClusters {
    /** How near (in screen pixels) two places must be to share a tag. */
    const val DEFAULT_THRESHOLD_PX = 44.0

    /**
     * Merges payments that would sit within [thresholdPx] of each other at [zoom] into clusters, greedily, biggest spender
     * first so the large tag keeps its exact position. The cell grid makes it linear for thousands of payments. Same input,
     * same output (ties broken by key). Payments within about 11 m (the stored precision) always merge at any zoom up to 19.
     */
    fun build(points: List<SpendPoint>, zoom: Double, thresholdPx: Double = DEFAULT_THRESHOLD_PX): List<PlaceCluster> {
        if (points.isEmpty()) return emptyList()
        val ordered = points.sortedWith(compareByDescending<SpendPoint> { it.amount }.thenBy { it.key })
        val px = ordered.map { TileMath.lonToX(it.lon, zoom) }
        val py = ordered.map { TileMath.latToY(it.lat, zoom) }
        val cell = thresholdPx
        val grid = HashMap<Long, MutableList<Int>>()
        fun cellKey(cx: Long, cy: Long) = cx * 4_000_003L + cy
        val groups = ArrayList<MutableList<Int>>()
        for (i in ordered.indices) {
            val cx = Math.floorDiv(px[i].toLong(), cell.toLong().coerceAtLeast(1L))
            val cy = Math.floorDiv(py[i].toLong(), cell.toLong().coerceAtLeast(1L))
            var joined = -1
            search@ for (dx in -1L..1L) for (dy in -1L..1L) {
                for (g in grid[cellKey(cx + dx, cy + dy)].orEmpty()) {
                    val ddx = px[groups[g][0]] - px[i]
                    val ddy = py[groups[g][0]] - py[i]
                    if (ddx * ddx + ddy * ddy <= thresholdPx * thresholdPx) { joined = g; break@search }
                }
            }
            if (joined >= 0) groups[joined].add(i)
            else {
                groups.add(mutableListOf(i))
                grid.getOrPut(cellKey(cx, cy)) { mutableListOf() }.add(groups.size - 1)
            }
        }
        return groups.map { g ->
            val members = g.map { ordered[it] }
            val total = members.fold(BigDecimal.ZERO) { s, p -> s + p.amount }
            val weight = total.toDouble().takeIf { it > 0.0 } ?: 1.0
            val lat = members.sumOf { it.lat * it.amount.toDouble() } / weight
            val lon = members.sumOf { it.lon * it.amount.toDouble() } / weight
            PlaceCluster(lat, lon, total, members.size, members.sortedByDescending { it.date })
        }
    }

    /** The zoom at which a cluster's members start to separate (for "tap to zoom in"), at most [TileMath.MAX_ZOOM]. */
    fun zoomToSplit(cluster: PlaceCluster, current: Double, thresholdPx: Double = DEFAULT_THRESHOLD_PX): Double {
        if (cluster.members.size < 2) return current
        var z = current + 1.0
        while (z < TileMath.MAX_ZOOM) {
            if (build(cluster.members, z, thresholdPx).size > 1) return z
            z += 1.0
        }
        return TileMath.MAX_ZOOM.toDouble()
    }
}
