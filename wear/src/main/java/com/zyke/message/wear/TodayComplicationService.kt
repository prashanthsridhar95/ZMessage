package com.zyke.message.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Today's remaining budget on a watch face. */
class TodayComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snapshot = SnapshotStore.latest(this)
        val value = if (snapshot == null || Format.isStale(snapshot)) "—" else Format.compact(snapshot.todayLeft, snapshot.masked)
        return data(request.complicationType, value)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, "₹1,079")

    private fun data(type: ComplicationType, value: String): ComplicationData? {
        fun plain(text: String) = PlainComplicationText.Builder(text).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(value), plain("Today left $value")).setTitle(plain("Left")).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain("$value left today"), plain("Today left $value")).build()
            else -> null
        }
    }
}
