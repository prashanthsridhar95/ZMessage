package com.zyke.message.data.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.places.CaptureDecision
import com.zyke.message.domain.places.PlaceCapture
import com.zyke.message.domain.places.PlaceKey
import com.zyke.message.domain.places.SpendPlace
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The ONLY place in the app that asks the phone where it is. It is called from the live SMS receiver for a text that is a
 * purchase ([PlaceCapture.decide]) and takes ONE quick fix: the network provider first, then GPS, then (at most) a last known
 * position younger than two minutes. No polling, no geofencing, no foreground service, no Google Play services. The position
 * is rounded to four decimals, kept with the accuracy class and the time, and never written to a log.
 *
 * Android 11 and later: the fix is requested with getCurrentLocation (API 30). Before that only a recent last known position
 * is used (the single-update call that older phones have was deprecated and is not used).
 */
internal object PlaceCapturer {
    /** The receiver's time budget is about ten seconds in all; the fix gets eight of them. */
    private const val FIX_TIMEOUT_MS = 8_000L
    private const val FRESH_MS = 120_000L

    /** Does nothing unless the switch is on, a permission is held and this text is a purchase. Never throws. */
    suspend fun onPurchaseText(context: Context, container: AppContainer, message: ClassifiedMessage) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        runCatching { capture(context, container, message) }
            .onFailure { com.zyke.message.data.diag.DevLog.d("places") { "capture failed: ${it.javaClass.simpleName}" } }
    }

    private suspend fun capture(context: Context, container: AppContainer, message: ClassifiedMessage) {
        val settings = container.spendPlaces.state.value.settings
        if (!settings.enabled || container.settings.settings.value.readingPaused) return
        when (val decision = PlaceCapture.decide(message, settings)) {
            is CaptureDecision.Skip -> { com.zyke.message.data.diag.DevLog.d("places") { "no place: ${decision.reason}" }; return }
            CaptureDecision.Capture -> Unit
        }
        if (!hasLocationPermission(context)) { com.zyke.message.data.diag.DevLog.d("places") { "no place: permission missing" }; return }
        val fix = fix(context) ?: run { com.zyke.message.data.diag.DevLog.d("places") { "no place: no fix" }; return }
        val accuracy = if (fix.hasAccuracy()) fix.accuracy else null
        val place = SpendPlace.make(PlaceKey.of(message.message), fix.latitude, fix.longitude, accuracy, System.currentTimeMillis()) ?: return
        container.spendPlaces.put(place)
        com.zyke.message.data.diag.DevLog.d("places") { "place saved (${place.accuracy.name.lowercase()})" }
    }

    /** Whether the phone's Location switch is on (for the settings card); reads a setting, asks for no position. */
    fun locationServicesOn(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
    }

    /** What the phone has granted, for the settings card: (fine or coarse, background or not needed). */
    fun permissionFacts(context: Context): Pair<Boolean, Boolean> {
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return hasLocationPermission(context) to background
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun fix(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { p ->
            runCatching { manager.isProviderEnabled(p) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val current = withTimeoutOrNull(FIX_TIMEOUT_MS) {
                var found: Location? = null
                for (p in providers) { found = current(manager, p); if (found != null) break }
                found
            }
            if (current != null) return current
        }
        // The fallback on every Android version: the freshest last known position, if it is under two minutes old.
        val now = System.currentTimeMillis()
        return providers.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .filter { now - it.time in 0..FRESH_MS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun current(manager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = android.os.CancellationSignal()
        cont.invokeOnCancellation { runCatching { signal.cancel() } }
        try {
            manager.getCurrentLocation(provider, signal, java.util.concurrent.Executor { it.run() }, java.util.function.Consumer<Location> { location ->
                if (cont.isActive) cont.resume(location)
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
