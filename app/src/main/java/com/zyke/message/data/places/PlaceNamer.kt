package com.zyke.message.data.places

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A street or area name for a saved place, best effort, only when the person allows names (the phone's geocoder may ask a remote
 * service, which then sees the coordinates; the settings page says so). Off the main thread, with a time limit; on any failure
 * the caller shows the coordinates instead. Answers are kept in memory for the session only. Never logged.
 */
internal object PlaceNamer {
    private const val TIMEOUT_MS = 6_000L
    private const val MAX_CACHED = 200
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_CACHED
    }

    fun available(): Boolean = runCatching { Geocoder.isPresent() }.getOrDefault(false)

    suspend fun name(context: Context, lat: Double, lon: Double): String? {
        val key = "%.4f,%.4f".format(Locale.ROOT, lat, lon)
        synchronized(cache) { cache[key] }?.let { return it }
        if (!available()) return null
        val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
        val found = withTimeoutOrNull(TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) async33(geocoder, lat, lon)
            else withContext(Dispatchers.IO) { blocking(geocoder, lat, lon) }
        }?.let(::describe)
        if (found != null) synchronized(cache) { cache[key] = found }
        return found
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun async33(geocoder: Geocoder, lat: Double, lon: Double): List<Address>? = suspendCancellableCoroutine { cont ->
        try {
            geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun blocking(geocoder: Geocoder, lat: Double, lon: Double): List<Address>? =
        runCatching { geocoder.getFromLocation(lat, lon, 1) }.getOrNull()

    /** "Indiranagar, Bengaluru": the area and the town, never a house number. */
    private fun describe(addresses: List<Address>): String? {
        val a = addresses.firstOrNull() ?: return null
        val area = a.featureName?.takeIf { it.isNotBlank() && !it.first().isDigit() } ?: a.thoroughfare ?: a.subLocality
        val parts = listOfNotNull(area, a.subLocality.takeIf { it != area }, a.locality ?: a.subAdminArea).filter { it.isNotBlank() }.distinct().take(2)
        return parts.joinToString(", ").takeIf { it.isNotBlank() }
    }
}
