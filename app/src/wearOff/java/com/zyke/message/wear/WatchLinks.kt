package com.zyke.message.wear

import android.content.Context

/** Default build: no watch support is compiled in. (`-PwithWear=true` swaps in src/wearOn.) */
@Suppress("UNUSED_PARAMETER")
object WatchLinks {
    fun create(context: Context): WatchLink = NoWatchLink
}
