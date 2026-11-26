package com.zyke.message.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.zyke.message.data.logo.LogoFetcher
import com.zyke.message.domain.classification.BrandLogo
import com.zyke.message.domain.classification.KnownEntityDomains
import com.zyke.message.domain.logo.LogoList
import com.zyke.message.domain.repository.SenderImageRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Pictures for senders and receivers, all kept in the app's private storage:
 *  - **chosen** by the person from their own photos, for any sender;
 *  - **brand logos** for well-known names, downloaded once (only if the person turned that on) and cached. A brand in the
 *    bundled logo list ([webLogos], see [LogoList]) is loaded from the web address the list gives for it, under the same
 *    switch and the same rules; otherwise the brand's own website is asked for its icon, as before.
 * A chosen picture always wins over a logo.
 */
class ImageStore(
    context: Context,
    /** Demo mode: no logo is looked up and no picture is fetched from the web. */
    private val offline: Boolean = false,
    private val logosEnabled: () -> Boolean
) : SenderImageRepository {

    private val appContext = context.applicationContext
    private val chosenDir = File(appContext.filesDir, "sender_images").apply { mkdirs() }
    private val logoDir = File(appContext.filesDir, "logos").apply { mkdirs() }
    private val logoFetcher = LogoFetcher()

    /** The bundled logo list, brand key to web address; empty until the container sets it. Asked only when a logo is looked up. */
    var webLogos: () -> Map<String, String> = { emptyMap() }
    // Sized by the bytes the pictures take, not by how many there are: a few large ones must not crowd out memory.
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val index = appContext.getSharedPreferences("sender_images", Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(index.getStringSet(KEYS, emptySet()).orEmpty().toSet())
    override val keysWithImage: StateFlow<Set<String>> = _keys.asStateFlow()

    /** Brand logos the person has turned off, by name, e.g. when a logo is wrong for what it is shown on. */
    private val _hidden = MutableStateFlow(index.getStringSet(HIDDEN, emptySet()).orEmpty().toSet())
    val hiddenLogos: StateFlow<Set<String>> = _hidden.asStateFlow()

    /** Tells screens showing a logo to ask again (the bundled list has just been read). */
    fun refresh() {
        _version.value += 1
    }

    /** Bumped whenever a picture changes, so screens showing one reload it. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /**
     * Saving outlives the screen that asked: the photo picker takes the person out of the app for a while, and a save
     * started on a screen's own scope is dropped without a word if that screen has left the composition by the time the
     * photo comes back. [onResult] is called on the main thread.
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun saveChosenDetached(key: String, uri: Uri, onResult: (Boolean) -> Unit) {
        saveScope.launch {
            val saved = saveChosen(key, uri)
            mainHandler.post { onResult(saved) }
        }
    }

    fun saveChosenBitmapDetached(key: String, source: Bitmap, onResult: (Boolean) -> Unit) {
        saveScope.launch {
            val saved = saveChosenBitmap(key, source)
            mainHandler.post { onResult(saved) }
        }
    }

    suspend fun saveChosen(key: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bitmap = runCatching { decodeSquare(uri) }.getOrNull() ?: return@withContext false
        keep(key, bitmap)
    }

    /** A picture picked on a web page, already downloaded: centre-cropped and scaled like one from the gallery. */
    suspend fun saveChosenBitmap(key: String, source: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val side = minOf(source.width, source.height)
        val bitmap = runCatching {
            val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(square, minOf(TARGET, side), minOf(TARGET, side), true)
        }.getOrNull() ?: return@withContext false
        keep(key, bitmap)
    }

    /** The picture behind a web page's image: a data address is read directly, an https one is downloaded. */
    suspend fun fetchPicked(address: String): Bitmap? = withContext(Dispatchers.IO) {
        if (offline) return@withContext null
        if (address.startsWith("data:image", ignoreCase = true)) {
            runCatching {
                val bytes = android.util.Base64.decode(address.substringAfter("base64,"), android.util.Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxSide() && bounds.outHeight / (sample * 2) >= maxSide()) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        } else {
            logoFetcher.fetchPicked(address)
        }
    }

    private fun keep(key: String, bitmap: Bitmap): Boolean {
        runCatching { File(chosenDir, fileName(key)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure { return false }
        cache.put(CHOSEN + key, bitmap)
        updateKeys(_keys.value + key)
        return true
    }

    /** Gives [toKey] the same chosen picture as [fromKey], as its own copy, so changing one later leaves the other alone. */
    suspend fun copyChosen(fromKey: String, toKey: String): Boolean = withContext(Dispatchers.IO) {
        val source = File(chosenDir, fileName(fromKey))
        if (!source.exists()) return@withContext false
        runCatching { source.copyTo(File(chosenDir, fileName(toKey)), overwrite = true) }.onFailure { return@withContext false }
        cache.remove(CHOSEN + toKey)
        updateKeys(_keys.value + toKey)
        true
    }

    /** See [SenderImageRepository.moveKeys]: the file is copied to its new name, then the old one is deleted. */
    @Synchronized
    override fun moveKeys(moves: Map<String, String>) {
        if (moves.isEmpty()) return
        var keys = _keys.value
        for ((from, to) in moves) {
            if (from !in keys || from == to) continue
            val source = File(chosenDir, fileName(from))
            if (to !in keys && source.isFile) {
                val copied = runCatching { source.copyTo(File(chosenDir, fileName(to)), overwrite = true) }.isSuccess
                if (!copied) continue
                keys = keys + to
            }
            source.delete()
            cache.remove(CHOSEN + from)
            cache.remove(CHOSEN + to)
            keys = keys - from
        }
        if (keys != _keys.value) updateKeys(keys)
    }

    /** Called with the key of a chosen picture that has just been removed, so the web address recorded for it can be forgotten too. */
    var onChosenRemoved: (String) -> Unit = {}

    fun removeChosen(key: String) {
        File(chosenDir, fileName(key)).delete()
        cache.remove(CHOSEN + key)
        updateKeys(_keys.value - key)
        onChosenRemoved(key)
    }

    /** The picture if it is already in memory, else null. Cheap enough to ask while a row is being composed. */
    fun peekChosen(key: String): Bitmap? = cache.get(CHOSEN + key)

    /**
     * The brand logo for [name] if it is already in memory, else null. Never reads a file or the network. [name] is the name
     * the person sees (their nickname when they gave one); [original] is the name in the message, tried second.
     */
    fun peekLogo(name: String, original: String? = null): Bitmap? {
        // A brand in the bundled list shows only its listed picture (never the website icon first, which would flip when the picture arrives).
        webLogoUrl(name, original)?.let { return cache.get(WEB + it) }
        val domain = logoDomain(name, original) ?: return null
        return cache.get(LOGO + domain)
    }

    /** The website whose logo is shown for [name], else for [original]: the first that is a known brand and not turned off. */
    private fun logoDomain(name: String, original: String?): String? =
        BrandLogo.resolve(BrandLogo.names(name, original), ::domainOf, ::isHidden)?.second

    /** The web address of the bundled list's logo for [name], else for [original]: the first that is listed and not turned off. */
    private fun webLogoUrl(name: String, original: String?): String? {
        val table = webLogos()
        return if (table.isEmpty()) null else LogoList.resolve(BrandLogo.names(name, original), table, ::isHidden)?.second
    }

    private fun hasWebLogo(name: String): Boolean = LogoList.keyOf(name)?.let { key -> key in webLogos() } == true

    /** The logo for a listed website (an airline's, see [com.zyke.message.domain.flight.Airlines.logoDomain]) if it is already in memory. */
    fun peekLogoDomain(domain: String): Bitmap? = if (KnownEntityDomains.isListed(domain)) cache.get(LOGO + domain) else null

    /** [KnownEntityDomains.domainFor] builds regexes on every call; a list of rows asks the same few names again and again. */
    private val domains = ConcurrentHashMap<String, String>()

    private fun domainOf(name: String): String? =
        domains.getOrPut(name) { KnownEntityDomains.domainFor(name) ?: "" }.ifEmpty { null }

    suspend fun loadChosen(key: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(CHOSEN + key) ?: decodeFile(File(chosenDir, fileName(key)))?.also { cache.put(CHOSEN + key, it) }
    }

    /** The cached logo for a well-known [name]; downloads it first when allowed and not tried recently. */
    suspend fun loadLogo(name: String, original: String? = null): Bitmap? = withContext(Dispatchers.IO) {
        // The bundled list first; when its picture cannot be had (offline, switched off, a bad address) the website icon is the fallback.
        val listed = webLogoUrl(name, original)?.let(::fetchWebLogo)
        if (listed != null) return@withContext listed
        val domain = logoDomain(name, original) ?: return@withContext null
        fetchLogo(domain)
    }

    /**
     * The same cache and the same rules (switched on by the person, never in the demo, not retried for a week after a miss)
     * for a website the app lists itself; anything not on the list is refused, so no other name can ever be sent.
     */
    suspend fun loadLogoDomain(domain: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!KnownEntityDomains.isListed(domain)) return@withContext null
        fetchLogo(domain)
    }

    private fun fetchLogo(domain: String): Bitmap? {
        cache.get(LOGO + domain)?.let { return it }
        val file = File(logoDir, fileName(domain))
        decodeFile(file)?.let { return it.also { bitmap -> cache.put(LOGO + domain, bitmap) } }

        if (offline || !logosEnabled() || recentlyFailed(domain) || !inFlight.add(domain)) return null
        return try {
            val bitmap = logoFetcher.fetch(domain)
            if (bitmap == null) {
                index.edit().putLong(FAILED + domain, System.currentTimeMillis()).apply()
                null
            } else {
                runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                cache.put(LOGO + domain, bitmap)
                _version.value += 1
                bitmap
            }
        } finally {
            inFlight.remove(domain)
        }
    }

    /**
     * The same cache and rules as [fetchLogo], for an address from the bundled list: the cached file is used first; otherwise it is
     * downloaded only when logos are switched on, never in the demo, one request at a time per address, and not retried for a week
     * after a miss. The picture is kept at no more than [TARGET] pixels a side. Nothing but the address itself is sent.
     */
    private fun fetchWebLogo(url: String): Bitmap? {
        cache.get(WEB + url)?.let { return it }
        val file = File(logoDir, fileName("web:$url"))
        decodeFile(file)?.let { return it.also { bitmap -> cache.put(WEB + url, bitmap) } }

        if (offline || !logosEnabled() || LogoList.cleanUrl(url) != url || recentlyFailed(url) || !inFlight.add(url)) return null
        return try {
            val downloaded = logoFetcher.fetchListed(url)
            if (downloaded == null) {
                index.edit().putLong(FAILED + url, System.currentTimeMillis()).apply()
                null
            } else {
                val bitmap = scaledDown(downloaded)
                runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                cache.put(WEB + url, bitmap)
                _version.value += 1
                bitmap
            }
        } finally {
            inFlight.remove(url)
        }
    }

    private fun scaledDown(source: Bitmap): Bitmap {
        val side = maxOf(source.width, source.height)
        if (side <= TARGET) return source
        val scale = TARGET.toFloat() / side
        return runCatching {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        }.getOrDefault(source)
    }

    /** True when the name is one a brand logo can be found for. */
    fun hasLogo(name: String): Boolean = domainOf(name) != null || hasWebLogo(name)

    /** True when a brand logo is shown for [name], else for [original] (a known brand that has not been turned off). */
    fun showsLogo(name: String, original: String?): Boolean = webLogoUrl(name, original) != null || logoDomain(name, original) != null

    /** True when a logo exists for [name] or [original] but the person turned it off. */
    fun logoTurnedOff(name: String, original: String?): Boolean {
        val names = BrandLogo.names(name, original)
        return names.any { domainOf(it) != null || hasWebLogo(it) } && !showsLogo(name, original)
    }

    fun isHidden(name: String): Boolean = norm(name) in _hidden.value

    /**
     * Shows the plain icon instead of a brand logo, for every name the logo could have been found by ([name] and [original]),
     * so changing the nickname later does not bring a wrong logo back by the other name.
     */
    fun hideLogo(name: String, original: String? = null) {
        val names = BrandLogo.withLogo(BrandLogo.names(name, original)) { domainOf(it) ?: it.takeIf(::hasWebLogo) }.ifEmpty { listOf(name) }
        setHidden(_hidden.value + names.map(::norm))
    }

    fun showLogo(name: String, original: String? = null) = setHidden(_hidden.value - BrandLogo.names(name, original).map(::norm).toSet())

    private fun setHidden(names: Set<String>) {
        index.edit().putStringSet(HIDDEN, names).apply()
        _hidden.value = names
        _version.value += 1
    }

    private fun norm(name: String) = name.trim().lowercase()

    /** Forgets every downloaded logo (for when the person turns the feature off). */
    fun clearLogos() {
        logoDir.listFiles()?.forEach { it.delete() }
        index.edit().apply { index.all.keys.filter { it.startsWith(FAILED) }.forEach(::remove) }.apply()
        cache.evictAll()
        _version.value += 1
    }

    private fun recentlyFailed(domain: String) =
        System.currentTimeMillis() - index.getLong(FAILED + domain, 0L) < RETRY_AFTER_MS

    private fun updateKeys(keys: Set<String>) {
        index.edit().putStringSet(KEYS, keys).apply()
        _keys.value = keys
        _version.value += 1
    }

    /** Read at no more than [MAX_SIDE] pixels a side, so a large file never costs its full size in memory. */
    private fun decodeFile(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide() && bounds.outHeight / (sample * 2) >= maxSide()) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            // Lite mode: a photo (JPEG, so no transparency to keep) takes half the memory at 16 bits a pixel.
            if (lite() && bounds.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    /** Lite mode, or a phone found to have little memory: pictures are kept small. */
    private fun lite(): Boolean = com.zyke.message.data.perf.LiteRuntime.isLite || com.zyke.message.data.perf.LowEnd.detected.value

    private fun maxSide(): Int = if (lite()) LITE_MAX_SIDE else MAX_SIDE

    private fun cacheBytes(): Int =
        if (lite()) MIN_CACHE_BYTES
        else (Runtime.getRuntime().maxMemory() / 32).coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong()).toInt()

    /** Centre-cropped to a square and scaled down, so a 12MP photo costs a few kilobytes. */
    private fun decodeSquare(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET && bounds.outHeight / (sample * 2) >= TARGET) sample *= 2
        val decoded = appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, minOf(TARGET, side), minOf(TARGET, side), true)
    }

    /**
     * The chosen pictures (not logos) as sender key to PNG bytes, for the backup; null when together they are more than
     * [maxBytes], so they are left out rather than read into memory.
     */
    fun chosenPicturesForBackup(maxBytes: Long): Map<String, ByteArray>? {
        val files = _keys.value.map { it to File(chosenDir, fileName(it)) }.filter { it.second.isFile }
        if (files.sumOf { it.second.length() } > maxBytes) return null
        return files.associate { (key, file) -> key to file.readBytes() }
    }

    /** Replaces the chosen pictures with those from a backup. The app is closed straight afterwards. */
    fun restoreChosenPictures(pictures: Map<String, ByteArray>) {
        chosenDir.listFiles()?.forEach { it.delete() }
        for ((key, bytes) in pictures) runCatching { File(chosenDir, fileName(key)).writeBytes(bytes) }
        index.edit().putStringSet(KEYS, pictures.keys.toSet()).commit()
        cache.evictAll()
        _keys.value = pictures.keys.toSet()
    }

    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".png"

    private companion object {
        const val KEYS = "keys"
        const val HIDDEN = "hidden-logos"
        const val FAILED = "failed-"
        const val CHOSEN = "c:"
        const val LOGO = "l:"
        const val WEB = "w:"
        const val MIN_CACHE_BYTES = 4 * 1024 * 1024
        const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        const val MAX_SIDE = 512
        const val LITE_MAX_SIDE = 128
        const val TARGET = 256
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
