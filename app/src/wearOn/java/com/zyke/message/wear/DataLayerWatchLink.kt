package com.zyke.message.wear

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.zyke.message.domain.wear.WatchSnapshot
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Puts the snapshot in the Wear OS Data Layer as one data item. The Data Layer carries it phone to watch over the
 * local Bluetooth/Wi-Fi link; no Zyke or third-party server is involved. The item is replaced each time, so the
 * watch only ever holds the latest figures. Needs the watch app installed with the same application id and signature.
 */
internal class DataLayerWatchLink(private val context: Context) : WatchLink {
    override suspend fun publish(snapshot: WatchSnapshot) {
        val request = PutDataMapRequest.create(WatchSnapshot.PATH).apply {
            snapshot.toMap().forEach { (key, value) -> dataMap.putString(key, value) }
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
