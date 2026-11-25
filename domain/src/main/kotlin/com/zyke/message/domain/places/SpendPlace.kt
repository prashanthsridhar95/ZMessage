package com.zyke.message.domain.places

/** How sure the fix was. A fix of 150 m or better is [PRECISE]; anything coarser is kept, flagged [ROUGH]. */
enum class PlaceAccuracy { PRECISE, ROUGH;
    companion object {
        const val PRECISE_METERS = 150f
        fun of(meters: Float?): PlaceAccuracy = if (meters != null && meters >= 0f && meters <= PRECISE_METERS) PRECISE else ROUGH
    }
}

/** Where one payment was made: coordinates rounded to four decimals (about 11 m), the accuracy class and the time of the fix. */
data class SpendPlace(
    val key: String,
    val lat: Double,
    val lon: Double,
    val accuracy: PlaceAccuracy,
    val at: Long
) {
    companion object {
        /** Four decimals: about 11 m. Nothing finer is ever stored. */
        fun round(degrees: Double): Double = Math.round(degrees * 10_000.0) / 10_000.0

        fun make(key: String, lat: Double, lon: Double, accuracyMeters: Float?, at: Long): SpendPlace? {
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return SpendPlace(key, round(lat), round(lon), PlaceAccuracy.of(accuracyMeters), at)
        }

        /** The key a nickname for a spot is filed under: three decimals (about 110 m), so every payment at one shop shares it. */
        fun spotKey(lat: Double, lon: Double): String = "%.3f,%.3f".format(java.util.Locale.ROOT, lat, lon)
    }
}

/** What the person chose. Everything here is off or conservative until they say otherwise. */
data class SpendPlaceSettings(
    val enabled: Boolean = false,
    /** The one-time explanation sheet has been read. */
    val explained: Boolean = false,
    /** Ask the phone's geocoder for a place name (it may contact a remote service). */
    val geocodeNames: Boolean = true,
    /** Places travel in the encrypted setup backup. */
    val includeInBackup: Boolean = false,
    val keepMonths: Int = DEFAULT_KEEP_MONTHS,
    /** Payee keys the person said to capture even though the app would skip them (online-looking shops). */
    val captureOverrides: Set<String> = emptySet(),
    /** Payee keys the person said never to capture. */
    val skipOverrides: Set<String> = emptySet()
) {
    companion object {
        const val DEFAULT_KEEP_MONTHS = 24
        val KEEP_CHOICES = listOf(3, 6, 12, 24, 0) // 0 = keep until deleted
    }
}

data class SpendPlaceState(
    val places: Map<String, SpendPlace> = emptyMap(),
    /** Nicknames by [SpendPlace.spotKey]. */
    val names: Map<String, String> = emptyMap(),
    val settings: SpendPlaceSettings = SpendPlaceSettings()
)
