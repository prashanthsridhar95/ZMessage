package com.zyke.message.wear

import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import android.content.ComponentName
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.zyke.message.domain.wear.WatchSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The latest snapshot the phone sent, kept in the watch's private preferences so the tile and complication can read it at once. */
object SnapshotStore {
    private const val PREFS = "snapshot"
    private val current = MutableStateFlow<WatchSnapshot?>(null)
    private var loaded = false

    fun flow(context: Context): StateFlow<WatchSnapshot?> {
        latest(context)
        return current
    }

    @Synchronized
    fun latest(context: Context): WatchSnapshot? {
        if (!loaded) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            current.value = WatchSnapshot.fromMap(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
            loaded = true
        }
        return current.value
    }

    /** Called by the listener service with the data item the phone put. */
    @Synchronized
    fun accept(context: Context, map: Map<String, String>) {
        val snapshot = WatchSnapshot.fromMap(map) ?: return
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply { map.forEach { (k, v) -> putString(k, v) } }.apply()
        loaded = true
        current.value = snapshot
        TileService.getUpdater(app).requestUpdate(TodayTileService::class.java)
        ComplicationDataSourceUpdateRequester.create(app, ComponentName(app, TodayComplicationService::class.java)).requestUpdateAll()
    }

    /** Reads whatever item the Data Layer already holds (the app was just installed, or the listener missed one). */
    fun pullExisting(context: Context) {
        val app = context.applicationContext
        Wearable.getDataClient(app).dataItems.addOnSuccessListener { items ->
            items.use {
                it.filter { item -> item.uri.path == WatchSnapshot.PATH }.forEach { item ->
                    val data = DataMapItem.fromDataItem(item).dataMap
                    accept(app, data.keySet().mapNotNull { k -> data.getString(k)?.let { v -> k to v } }.toMap())
                }
            }
        }
    }
}
