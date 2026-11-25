package com.zyke.message.data.maps

import com.zyke.message.domain.places.TileId
import java.io.File

/**
 * Map squares kept on the phone so the map works offline and is not asked for twice: files under the app's cache folder
 * (`map_tiles/z/x/y.png`), which the system may clear and no backup takes. A square is good for [TTL_MS] (30 days; OpenStreetMap
 * asks for at least a week); the folder is trimmed to [MAX_BYTES] by dropping the oldest. Pure file handling, no network.
 */
internal class TileCache(cacheDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val root = File(cacheDir, "map_tiles")
    private var writesSinceTrim = 0

    private fun file(t: TileId) = File(root, "${t.z}/${t.x}/${t.y}.png")

    /** The bytes if the square is stored and not too old (when [allowStale], whatever age: offline is better than blank). */
    fun read(t: TileId, allowStale: Boolean = false): ByteArray? = runCatching {
        val f = file(t)
        if (!f.isFile) return null
        if (!allowStale && clock() - f.lastModified() > TTL_MS) return null
        f.readBytes()
    }.getOrNull()

    fun isFresh(t: TileId): Boolean = runCatching { file(t).let { it.isFile && clock() - it.lastModified() <= TTL_MS } }.getOrDefault(false)

    fun write(t: TileId, bytes: ByteArray) {
        runCatching {
            val f = file(t)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            f.setLastModified(clock())
        }
        if (++writesSinceTrim >= TRIM_EVERY) { writesSinceTrim = 0; trim() }
    }

    /** Drops the oldest squares until the folder is under the size limit. */
    fun trim() {
        runCatching {
            val files = root.walkTopDown().filter { it.isFile }.toList()
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_BYTES * 8 / 10) break
                total -= f.length()
                f.delete()
            }
        }
    }

    fun clear() { runCatching { root.deleteRecursively() } }

    companion object {
        const val TTL_MS = 30L * 24 * 3_600_000L
        const val MAX_BYTES = 40L * 1024 * 1024
        private const val TRIM_EVERY = 40
    }
}
