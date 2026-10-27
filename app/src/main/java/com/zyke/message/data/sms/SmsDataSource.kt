package com.zyke.message.data.sms

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.zyke.message.domain.model.SmsMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Reads the device SMS inbox through the telephony content provider. */
class SmsDataSource(private val contentResolver: ContentResolver) {

    fun readInbox(): List<SmsMessage> {
        val cursor = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            PROJECTION,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return emptyList()

        return cursor.use {
            val id = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val address = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val read = it.getColumnIndexOrThrow(Telephony.Sms.READ)

            buildList {
                while (it.moveToNext()) {
                    add(
                        SmsMessage(
                            id = it.getLong(id),
                            sender = it.getString(address).orEmpty(),
                            body = it.getString(body).orEmpty(),
                            date = it.getLong(date),
                            isRead = it.getInt(read) == 1
                        )
                    )
                }
            }
        }
    }

    /** Emits once on collection, then on every change to the SMS provider. */
    fun inboxChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }

    private companion object {
        val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )
    }
}
