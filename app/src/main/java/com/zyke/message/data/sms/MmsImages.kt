package com.zyke.message.data.sms

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.zyke.message.domain.mms.MmsMessages
import com.zyke.message.domain.mms.MmsPart

/**
 * The pictures of one picture (MMS) message, read from the phone's MMS provider only when that message is open: at most
 * [MmsMessages.MAX_IMAGES], each shrunk while it is read so a large photo never costs its full size in memory. No network is used.
 * Call it off the main thread.
 */
object MmsImages {
    private val PART: Uri = Uri.parse("content://mms/part")

    fun load(resolver: ContentResolver, messageId: Long): List<Bitmap> {
        // The demo never reads the phone's providers.
        if (com.zyke.message.data.demo.DemoMode.active) return emptyList()
        val rowId = MmsMessages.rowIdOf(messageId) ?: return emptyList()
        return try {
            val parts = resolver.query(PART, arrayOf("_id", "ct"), "mid = ?", arrayOf(rowId.toString()), "_id ASC")?.use {
                val id = it.getColumnIndexOrThrow("_id")
                val type = it.getColumnIndexOrThrow("ct")
                buildList { while (it.moveToNext()) add(MmsPart(it.getLong(id), it.getString(type).orEmpty(), seq = it.position)) }
            }.orEmpty()
            MmsMessages.imagePartIds(parts).mapNotNull { decode(resolver, it) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            emptyList()
        }
    }

    private fun decode(resolver: ContentResolver, partId: Long): Bitmap? {
        val uri = Uri.parse("content://mms/part/$partId")
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val lite = com.zyke.message.data.perf.LiteRuntime.isLite || com.zyke.message.data.perf.LowEnd.detected.value
            val sample = MmsMessages.sampleSize(bounds.outWidth, bounds.outHeight, MmsMessages.maxSide(lite))
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    // A photo (JPEG, no transparency to keep) takes half the memory at 16 bits a pixel on a phone with little memory.
                    if (lite && bounds.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
                })
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
