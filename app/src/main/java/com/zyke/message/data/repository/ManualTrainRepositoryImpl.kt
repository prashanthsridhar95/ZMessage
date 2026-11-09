package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.train.ManualTrain
import com.zyke.message.domain.train.ManualTrainRepository
import com.zyke.message.domain.train.ManualTrainStore
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Train journeys the person added by hand, in private preferences (`manual_trains`, which goes into the backup of the person's setup).
 * The changes themselves are in [ManualTrainStore] (atomic, tested); this only loads and saves. In Demo mode the preferences are in
 * memory (see DemoContext), so nothing is kept.
 */
class ManualTrainRepositoryImpl private constructor(
    private val prefs: android.content.SharedPreferences,
    private val store: ManualTrainStore
) : ManualTrainRepository by store {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("manual_trains", Context.MODE_PRIVATE))

    private constructor(prefs: android.content.SharedPreferences) : this(prefs, ManualTrainStore(load(prefs)) { save(prefs, it) })

    private companion object {
        const val TRAINS = "trains"

        fun save(prefs: android.content.SharedPreferences, trains: List<ManualTrain>) {
            val array = JSONArray()
            trains.forEach { t ->
                array.put(
                    JSONObject().put("id", t.id).put("n", t.number).put("d", t.date.toString())
                        .put("bc", t.boardingCode ?: JSONObject.NULL).put("bn", t.boardingName ?: JSONObject.NULL)
                        .put("p", t.pnrNote ?: JSONObject.NULL).put("nm", t.name ?: JSONObject.NULL)
                        .put("dc", t.destinationCode ?: JSONObject.NULL).put("dn", t.destinationName ?: JSONObject.NULL)
                        .put("dt", t.departure?.toString() ?: JSONObject.NULL).put("at", t.arrival?.toString() ?: JSONObject.NULL)
                        .put("ao", t.arrivalDayOffset).put("bd", t.boardingDay).put("c", t.confirmed).put("t", t.addedAt)
                )
            }
            prefs.edit().putString(TRAINS, array.toString()).apply()
        }

        fun load(prefs: android.content.SharedPreferences): List<ManualTrain> = runCatching {
            val array = JSONArray(prefs.getString(TRAINS, "[]"))
            (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val o = array.getJSONObject(i)
                    fun text(key: String) = if (o.isNull(key)) null else o.getString(key)
                    ManualTrain(
                        id = o.getString("id"), number = o.getString("n"), date = LocalDate.parse(o.getString("d")),
                        boardingCode = text("bc"), boardingName = text("bn"), pnrNote = text("p"), name = text("nm"),
                        destinationCode = text("dc"), destinationName = text("dn"),
                        departure = text("dt")?.let { LocalTime.parse(it) }, arrival = text("at")?.let { LocalTime.parse(it) },
                        arrivalDayOffset = o.optInt("ao"), boardingDay = o.optInt("bd", 1).coerceAtLeast(1),
                        confirmed = o.optBoolean("c"), addedAt = o.optLong("t")
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
