package com.zyke.message.wear

import android.content.Context

/** Built with `-PwithWear=true`: the Wear OS Data Layer. */
object WatchLinks {
    fun create(context: Context): WatchLink = DataLayerWatchLink(context.applicationContext)
}
