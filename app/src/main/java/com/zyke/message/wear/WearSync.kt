package com.zyke.message.wear

import com.zyke.message.domain.wear.WatchSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends the watch a fresh snapshot, but only when something it shows changed or its copy is getting old, so a busy
 * inbox does not keep the Bluetooth link awake. Safe to call as often as the state changes.
 */
class WearSync(private val link: WatchLink) {
    private val lock = Mutex()
    private var last: WatchSnapshot? = null

    suspend fun offer(snapshot: WatchSnapshot) = lock.withLock {
        if (!WatchSnapshot.shouldPush(last, snapshot)) return@withLock
        // A watch that is not there must not break the app; the next change tries again.
        runCatching { link.publish(snapshot) }.onSuccess { last = snapshot }
        Unit
    }
}
