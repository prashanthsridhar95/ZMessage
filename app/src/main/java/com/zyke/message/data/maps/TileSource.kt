package com.zyke.message.data.maps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.zyke.message.domain.places.TileId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The map's pictures for the squares on screen. Made when the map page opens and closed when it closes; nothing is requested
 * otherwise. [bitmap] answers at once from memory (or null while a square loads); [request] asks for the visible squares only,
 * from disk if stored, else from [TileFetcher], two at a time, and bumps [version] when something arrived so the map redraws.
 * With [offline] (Demo mode, or the person having no network) nothing is fetched and the disk is only read.
 */
internal class TileSource(cacheDir: File, private val offline: Boolean, private val fetcher: TileFetcher = TileFetcher()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val disk = TileCache(cacheDir)
    private val gate = Semaphore(MAX_CONCURRENT)
    private val pending = ConcurrentHashMap.newKeySet<TileId>()
    private val failedAt = ConcurrentHashMap<TileId, Long>()
    private val memory = object : LruCache<TileId, Bitmap>(MEMORY_TILES) {}
    private val counter = MutableStateFlow(0)
    val version: StateFlow<Int> = counter.asStateFlow()

    fun bitmap(tile: TileId): Bitmap? = memory.get(tile)

    /** Loads these squares (nearest the middle first, as given); ones already held or in flight are skipped. */
    fun request(tiles: List<TileId>) {
        for (t in tiles.take(MAX_VISIBLE)) {
            if (memory.get(t) != null || !pending.add(t)) continue
            if (System.currentTimeMillis() - (failedAt[t] ?: 0L) < RETRY_MS) { pending.remove(t); continue }
            scope.launch {
                try {
                    gate.withPermit {
                        var bytes = disk.read(t)
                        if (bytes == null && !offline) {
                            bytes = fetcher.fetch(t)?.also { disk.write(t, it) }
                        }
                        if (bytes == null) bytes = disk.read(t, allowStale = true)
                        val decoded = bytes?.let { decode(it) }
                        if (decoded != null) { memory.put(t, decoded); counter.value += 1 }
                        else failedAt[t] = System.currentTimeMillis()
                    }
                } finally {
                    pending.remove(t)
                }
            }
        }
    }

    /** A square decoded with a size check, so a broken or huge picture cannot take the memory. */
    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    fun close() { scope.cancel(); memory.evictAll() }

    private companion object {
        const val MAX_CONCURRENT = 2
        const val MAX_VISIBLE = 40
        const val MEMORY_TILES = 96
        const val RETRY_MS = 30_000L
    }
}
