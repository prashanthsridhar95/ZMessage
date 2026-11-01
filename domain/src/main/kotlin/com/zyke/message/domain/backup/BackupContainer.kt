package com.zyke.message.domain.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a backup holds once opened: the setup snapshot (JSON) and, if the backup has any, the chosen sender pictures. */
class BackupContents(
    val setup: ByteArray,
    /** Sender key to PNG bytes. */
    val pictures: Map<String, ByteArray>,
    /** True when this backup was made with pictures considered (even if there were none); false for older files and when they were too big. */
    val picturesIncluded: Boolean,
    /** True when the pictures were left out because together they were over [BackupContainer.MAX_PICTURE_BYTES]. */
    val picturesSkipped: Boolean
)

/**
 * The plain content inside an encrypted backup file. Version 1 (manual exports before pictures existed) is the bare
 * JSON snapshot; version 2 is a zip (java.util.zip, no libraries) with `setup.json`, `meta.json` and one
 * `pictures/<base64url of the sender key>` entry per chosen picture. [unpack] reads both.
 */
object BackupContainer {
    const val MAX_PICTURE_BYTES = 20L * 1024 * 1024
    private const val MAX_SETUP_BYTES = 32L * 1024 * 1024
    private const val MAX_ENTRIES = 5_000
    private const val SETUP = "setup.json"
    private const val META = "meta.json"
    private const val PICTURES = "pictures/"

    class Packed(val bytes: ByteArray, val picturesSkipped: Boolean)

    /** Total size at which pictures are left out. */
    fun fitsPictures(totalBytes: Long) = totalBytes <= MAX_PICTURE_BYTES

    fun pack(setup: ByteArray, pictures: Map<String, ByteArray>): Packed {
        val total = pictures.values.sumOf { it.size.toLong() }
        val skipped = !fitsPictures(total)
        val kept = if (skipped) emptyMap() else pictures
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(META))
            zip.write("{\"container\":2,\"pictures\":${!skipped},\"skipped\":$skipped}".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(SETUP))
            zip.write(setup)
            zip.closeEntry()
            val b64 = Base64.getUrlEncoder().withoutPadding()
            for ((key, bytes) in kept) {
                zip.putNextEntry(ZipEntry(PICTURES + b64.encodeToString(key.toByteArray(Charsets.UTF_8))))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return Packed(out.toByteArray(), skipped)
    }

    @Throws(BackupException::class)
    fun unpack(bytes: ByteArray): BackupContents {
        if (bytes.isEmpty()) throw BackupException.Corrupt()
        // Version 1: the snapshot itself, which is a JSON object.
        if (bytes[0] == '{'.code.toByte()) return BackupContents(bytes, emptyMap(), picturesIncluded = false, picturesSkipped = false)
        // Version 2: a zip, which starts with "PK".
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) throw BackupException.Corrupt()
        var setup: ByteArray? = null
        var included = false
        var skipped = false
        val pictures = LinkedHashMap<String, ByteArray>()
        var pictureBytes = 0L
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw BackupException.Corrupt()
                    val limit = if (entry.name == SETUP) MAX_SETUP_BYTES else MAX_PICTURE_BYTES
                    val data = readLimited(zip, limit)
                    when {
                        entry.name == SETUP -> setup = data
                        entry.name == META -> {
                            val meta = runCatching { MiniJson.parse(String(data, Charsets.UTF_8)) as? Map<*, *> }.getOrNull()
                            included = meta?.get("pictures") == true
                            skipped = meta?.get("skipped") == true
                        }
                        entry.name.startsWith(PICTURES) -> {
                            pictureBytes += data.size
                            if (pictureBytes > MAX_PICTURE_BYTES) throw BackupException.Corrupt()
                            val key = try {
                                String(Base64.getUrlDecoder().decode(entry.name.removePrefix(PICTURES)), Charsets.UTF_8)
                            } catch (e: IllegalArgumentException) {
                                throw BackupException.Corrupt()
                            }
                            pictures[key] = data
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw BackupException.Corrupt()
        }
        return BackupContents(setup ?: throw BackupException.Corrupt(), pictures, included, skipped)
    }

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw BackupException.Corrupt()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
