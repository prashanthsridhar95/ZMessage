package com.zyke.message.data.logo

import android.content.Context
import com.zyke.message.domain.logo.LogoList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The logo list that ships with the app (`assets/known_logos.json`, format in [LogoList]): which web address each well-known
 * brand's logo is loaded from. It is read once, off the main thread, shortly after the app starts, and a missing or damaged file is an
 * empty list. Until it has been read, [table] is empty, so no screen ever waits on the file. The hosts in it are the only ones the app ever asks for these logos (see docs/PRIVACY.md).
 */
class BundledLogos(private val context: Context) {

    @Volatile private var loaded: Map<String, String>? = null

    /** Brand key to web address; empty until [loadInBackground] has finished, and when the file is missing, too large or not the documented format. */
    val table: Map<String, String> get() = loaded ?: emptyMap()

    /** Reads the file once on a background thread, then calls [onLoaded] (only when it holds something) so screens showing a logo ask again. */
    fun loadInBackground(onLoaded: () -> Unit) {
        if (loaded != null) return
        scope.launch {
            val read = load()
            loaded = read
            if (read.isNotEmpty()) onLoaded()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun load(): Map<String, String> = runCatching {
        val bytes = context.assets.open(FILE).use { input ->
            val buffer = ByteArray(LogoList.MAX_TEXT + 1)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            buffer.copyOf(size)
        }
        LogoList.parse(String(bytes, Charsets.UTF_8))?.let { LogoList.table(it.entries) }.orEmpty()
    }.getOrDefault(emptyMap())

    companion object {
        const val FILE = "known_logos.json"
    }
}
