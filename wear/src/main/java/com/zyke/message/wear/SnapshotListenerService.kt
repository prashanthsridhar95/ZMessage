package com.zyke.message.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.zyke.message.domain.wear.WatchSnapshot

class SnapshotListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchSnapshot.PATH }.forEach { event ->
            val data = DataMapItem.fromDataItem(event.dataItem).dataMap
            SnapshotStore.accept(this, data.keySet().mapNotNull { k -> data.getString(k)?.let { v -> k to v } }.toMap())
        }
    }
}
