package com.zyke.message.wear

import com.zyke.message.domain.wear.WatchSnapshot

/**
 * The only thing the phone app knows about a watch: somewhere to hand a [WatchSnapshot]. The real implementation
 * (the Wear OS Data Layer, which talks to the paired watch over Bluetooth, never the internet) lives in the optional
 * `wearOn` source set, built with `-PwithWear=true`; by default this does nothing.
 */
interface WatchLink {
    /** Hands the snapshot over; returns quietly when no watch is paired. Called off the main thread. */
    suspend fun publish(snapshot: WatchSnapshot)
}

object NoWatchLink : WatchLink {
    override suspend fun publish(snapshot: WatchSnapshot) = Unit
}
