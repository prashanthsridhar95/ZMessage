package com.zyke.message.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.zyke.message.data.logo.LogoFetcher
import com.zyke.message.domain.classification.KnownEntityDomains
import com.zyke.message.domain.repository.SenderImageRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Pictures for senders and receivers, all kept in the app's private storage:
 *  - **chosen** by the person from their own photos, for any sender;
 *  - **brand logos** for well-known names, downloaded once (only if the person turned that on) and cached.
 * A chosen picture always wins over a logo.
 */
class ImageStore(
    context: Context,
    private val logosEnabled: () -> Boolean
) : SenderImageRepository {

    private val appContext = context.applicationContext
    private val chosenDir = File(appContext.filesDir, "sender_images").apply { mkdirs() }
    private val logoDir = File(appContext.filesDir, "logos").apply { mkdirs() }
    private val logoFetcher = LogoFetcher()
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

    /** Bumped whenever a picture changes, so screens showing one reload it. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

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
        if (address.startsWith("data:image", ignoreCase = true)) {
            runCatching {
                val bytes = android.util.Base64.decode(address.substringAfter("base64,"), android.util.Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= MAX_SIDE && bounds.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2
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

    fun removeChosen(key: String) {
        File(chosenDir, fileName(key)).delete()
        cache.remove(CHOSEN + key)
        updateKeys(_keys.value - key)
    }

    /** The picture if it is already in memory, else null. Cheap enough to ask while a row is being composed. */
    fun peekChosen(key: String): Bitmap? = cache.get(CHOSEN + key)

    /** The brand logo for [name] if it is already in memory, else null. Never reads a file or the network. */
    fun peekLogo(name: String): Bitmap? {
        if (isHidden(name)) return null
        val domain = domainOf(name) ?: return null
        return cache.get(LOGO + domain)
    }

    /** [KnownEntityDomains.domainFor] builds regexes on every call; a list of rows asks the same few names again and again. */
    private val domains = ConcurrentHashMap<String, String>()

    private fun domainOf(name: String): String? =
        domains.getOrPut(name) { KnownEntityDomains.domainFor(name) ?: "" }.ifEmpty { null }

    suspend fun loadChosen(key: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(CHOSEN + key) ?: decodeFile(File(chosenDir, fileName(key)))?.also { cache.put(CHOSEN + key, it) }
    }

    /** The cached logo for a well-known [name]; downloads it first when allowed and not tried recently. */
    suspend fun loadLogo(name: String): Bitmap? = withContext(Dispatchers.IO) {
        if (isHidden(name)) return@withContext null
        val domain = domainOf(name) ?: return@withContext null
        cache.get(LOGO + domain)?.let { return@withContext it }
        val file = File(logoDir, fileName(domain))
        decodeFile(file)?.let { return@withContext it.also { bitmap -> cache.put(LOGO + domain, bitmap) } }

        if (!logosEnabled() || recentlyFailed(domain) || !inFlight.add(domain)) return@withContext null
        try {
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

    /** True when the name is one a brand logo can be found for. */
    fun hasLogo(name: String): Boolean = domainOf(name) != null

    fun isHidden(name: String): Boolean = norm(name) in _hidden.value

    /** Shows the plain icon for this name instead of a brand logo. */
    fun hideLogo(name: String) = setHidden(_hidden.value + norm(name))

    fun showLogo(name: String) = setHidden(_hidden.value - norm(name))

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
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE && bounds.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 32).coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong()).toInt()

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
        const val MIN_CACHE_BYTES = 4 * 1024 * 1024
        const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        const val MAX_SIDE = 512
        const val TARGET = 256
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
