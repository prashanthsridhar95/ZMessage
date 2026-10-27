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
    private val cache = object : LruCache<String, Bitmap>(CACHE_ENTRIES) {}
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
        runCatching { File(chosenDir, fileName(key)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure { return@withContext false }
        cache.put(CHOSEN + key, bitmap)
        updateKeys(_keys.value + key)
        true
    }

    fun removeChosen(key: String) {
        File(chosenDir, fileName(key)).delete()
        cache.remove(CHOSEN + key)
        updateKeys(_keys.value - key)
    }

    suspend fun loadChosen(key: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(CHOSEN + key) ?: decodeFile(File(chosenDir, fileName(key)))?.also { cache.put(CHOSEN + key, it) }
    }

    /** The cached logo for a well-known [name]; downloads it first when allowed and not tried recently. */
    suspend fun loadLogo(name: String): Bitmap? = withContext(Dispatchers.IO) {
        if (isHidden(name)) return@withContext null
        val domain = KnownEntityDomains.domainFor(name) ?: return@withContext null
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
    fun hasLogo(name: String): Boolean = KnownEntityDomains.domainFor(name) != null

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

    private fun decodeFile(file: File): Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null

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

    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".png"

    private companion object {
        const val KEYS = "keys"
        const val HIDDEN = "hidden-logos"
        const val FAILED = "failed-"
        const val CHOSEN = "c:"
        const val LOGO = "l:"
        const val CACHE_ENTRIES = 64
        const val TARGET = 256
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
