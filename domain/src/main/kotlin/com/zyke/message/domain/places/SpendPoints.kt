package com.zyke.message.domain.places

import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** One payment with a place: what the map draws and sums. [amount] is always positive spending. */
data class SpendPoint(
    val key: String,
    val messageId: Long,
    val lat: Double,
    val lon: Double,
    val amount: BigDecimal,
    val date: Long,
    val merchant: String,
    val category: MessageCategory,
    val rough: Boolean
)

/** The periods the map's filter chips offer. */
enum class PlacePeriod(val label: String) {
    THIS_MONTH("This month"), THREE_MONTHS("3 months"), YEAR("Year"), ALL("All");

    /** The earliest instant (epoch millis) that belongs to the period, or Long.MIN_VALUE for [ALL]. */
    fun since(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = when (this) {
            THIS_MONTH -> today.withDayOfMonth(1)
            THREE_MONTHS -> today.withDayOfMonth(1).minusMonths(2)
            YEAR -> today.withDayOfMonth(1).minusMonths(11)
            ALL -> return Long.MIN_VALUE
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

object SpendPoints {
    /**
     * The places joined to the person's messages. Only a payment [Counting] calls spending counts (so a payment the person left
     * out of totals, a transfer or a card bill answered since, leaves the map too), and the place is looked up by [PlaceKey]
     * and its neighbouring minutes. Each stored place is used once.
     */
    fun build(messages: List<ClassifiedMessage>, places: Map<String, SpendPlace>): List<SpendPoint> {
        if (places.isEmpty()) return emptyList()
        val used = HashSet<String>()
        val out = ArrayList<SpendPoint>()
        for (m in messages) {
            val amount = Counting.spend(m) ?: continue
            val place = PlaceKey.nearby(m.message).firstNotNullOfOrNull { k -> places[k]?.takeIf { used.add(k) } } ?: continue
            out.add(SpendPoint(place.key, m.id, place.lat, place.lon, amount, m.message.date, m.merchant.name, m.category, place.accuracy == PlaceAccuracy.ROUGH))
        }
        return out
    }

    fun filter(points: List<SpendPoint>, period: PlacePeriod, category: MessageCategory?, now: Long, zone: ZoneId): List<SpendPoint> {
        val since = period.since(now, zone)
        return points.filter { it.date >= since && (category == null || it.category == category) }
    }

    /** The stored place for a message, if it has one. */
    fun placeOf(message: ClassifiedMessage, places: Map<String, SpendPlace>): SpendPlace? =
        PlaceKey.nearby(message.message).firstNotNullOfOrNull { places[it] }

    /** What is inside the screen: the sum and count shown as "₹18,400 across 23 payments in view". */
    data class InView(val total: BigDecimal, val count: Int)

    fun inView(points: List<SpendPoint>, view: MapView, width: Double, height: Double): InView {
        var total = BigDecimal.ZERO
        var n = 0
        for (p in points) {
            val (x, y) = TileMath.toScreen(p.lat, p.lon, view, width, height)
            if (x in 0.0..width && y in 0.0..height) { total += p.amount; n++ }
        }
        return InView(total, n)
    }
}
