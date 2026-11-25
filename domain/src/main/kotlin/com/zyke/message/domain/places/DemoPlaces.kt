package com.zyke.message.domain.places

/**
 * Made-up places for Demo mode, around a fictional city (the coordinates mean nothing; the demo map draws a blank grid and
 * never touches the phone's location or the internet). A payee always lands in the same neighbourhood, so the map shows a few
 * busy spots and some quiet ones, as a real one would.
 */
object DemoPlaces {
    const val CENTER_LAT = 15.0000
    const val CENTER_LON = 75.0000

    private val SPOTS = listOf(
        0.000 to 0.000, 0.012 to 0.008, -0.010 to 0.015, 0.020 to -0.012, -0.018 to -0.009, 0.006 to -0.022,
        -0.004 to 0.027, 0.031 to 0.019, -0.027 to 0.004, 0.015 to 0.034, -0.021 to -0.028, 0.040 to -0.003
    )

    private fun hash(text: String): Int { var h = 17; for (c in text) h = h * 31 + c.code; return h and 0x7fffffff }

    fun place(key: String, payeeName: String, at: Long): SpendPlace {
        val spot = SPOTS[hash(payeeName.lowercase()) % SPOTS.size]
        val jitter = hash(key)
        val dLat = ((jitter % 41) - 20) / 10_000.0
        val dLon = (((jitter / 41) % 41) - 20) / 10_000.0
        return SpendPlace(key, SpendPlace.round(CENTER_LAT + spot.first + dLat), SpendPlace.round(CENTER_LON + spot.second + dLon), PlaceAccuracy.PRECISE, at)
    }
}
