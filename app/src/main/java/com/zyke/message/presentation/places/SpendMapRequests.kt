package com.zyke.message.presentation.places

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf

/** Where the map should open: on a place (from a payment's details) or on all of the person's places (null). */
data class MapRequest(val lat: Double?, val lon: Double?, val zoom: Double?, val nonce: Long = System.nanoTime())

/**
 * Opens the Spending map page from anywhere (the Insights card, a payment's details, Settings). AppRoot draws the page while
 * [open] holds a request; nothing is read or requested until the page is shown.
 */
object SpendMapRequests {
    val open = mutableStateOf<MapRequest?>(null)

    fun show(lat: Double? = null, lon: Double? = null, zoom: Double? = null) { open.value = MapRequest(lat, lon, zoom) }

    fun close() { open.value = null }
}

/** One line in AppRoot: draws the map page while one is asked for. */
@Composable
fun SpendMapHost() {
    val request by SpendMapRequests.open
    request?.let { SpendMapPage(it) { SpendMapRequests.close() } }
}
