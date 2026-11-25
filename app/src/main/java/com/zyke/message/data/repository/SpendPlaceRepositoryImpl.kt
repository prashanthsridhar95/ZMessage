package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zyke.message.domain.places.PlaceAccuracy
import com.zyke.message.domain.places.SpendPlace
import com.zyke.message.domain.places.SpendPlaceRepository
import com.zyke.message.domain.places.SpendPlaceSettings
import com.zyke.message.domain.places.SpendPlaceState
import com.zyke.message.domain.places.SpendPlaceStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The places of purchases and the choices about them, in two private preference files. `spend_places` holds the places
 * and the names the person gave to spots; it is left out of every backup unless the person turned on "Include places in
 * my backup" (see PrefsBackup). `spend_places_settings` holds the switch and the choices, and is never backed up.
 * Coordinates are stored already rounded to four decimals. The logic is in [SpendPlaceStore] (atomic, tested); this loads and saves.
 */
class SpendPlaceRepositoryImpl private constructor(
    private val places: SharedPreferences,
    private val settings: SharedPreferences,
    private val store: SpendPlaceStore
) : SpendPlaceRepository by store {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PLACES_FILE, Context.MODE_PRIVATE),
        context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    )

    private constructor(places: SharedPreferences, settings: SharedPreferences) : this(places, settings, SpendPlaceStore(load(places, settings), saver(places, settings)))

    private companion object {
        const val PLACES_FILE = "spend_places"
        const val SETTINGS_FILE = "spend_places_settings"

        /** Written only when it changed: a capture rewrites the places, never the settings. */
        fun saver(places: SharedPreferences, settings: SharedPreferences): (SpendPlaceState) -> Unit {
            var lastSettings: SpendPlaceSettings? = null
            var lastNames: Map<String, String>? = null
            var lastPlaces: Map<String, SpendPlace>? = null
            return { s ->
                if (s.places !== lastPlaces) {
                    val array = JSONArray()
                    s.places.values.forEach { p -> array.put(JSONObject().put("k", p.key).put("a", p.lat).put("o", p.lon).put("q", if (p.accuracy == PlaceAccuracy.PRECISE) 1 else 0).put("t", p.at)) }
                    places.edit().putString("places", array.toString()).apply()
                    lastPlaces = s.places
                }
                if (s.names != lastNames) {
                    val names = JSONObject()
                    s.names.forEach { (k, v) -> names.put(k, v) }
                    places.edit().putString("names", names.toString()).apply()
                    lastNames = s.names
                }
                if (s.settings != lastSettings) {
                    val c = s.settings
                    settings.edit().putBoolean("enabled", c.enabled).putBoolean("explained", c.explained).putBoolean("prompted", c.prompted).putBoolean("geocode", c.geocodeNames)
                        .putBoolean("backup", c.includeInBackup).putBoolean("pictures", c.mapPictures).putInt("months", c.keepMonths)
                        .putStringSet("capture", c.captureOverrides).putStringSet("skip", c.skipOverrides).apply()
                    lastSettings = c
                }
            }
        }

        fun load(places: SharedPreferences, settings: SharedPreferences): SpendPlaceState {
            val map = LinkedHashMap<String, SpendPlace>()
            runCatching {
                val array = JSONArray(places.getString("places", "[]"))
                for (i in 0 until array.length()) runCatching {
                    val o = array.getJSONObject(i)
                    val p = SpendPlace(o.getString("k"), o.getDouble("a"), o.getDouble("o"), if (o.optInt("q", 0) == 1) PlaceAccuracy.PRECISE else PlaceAccuracy.ROUGH, o.optLong("t"))
                    if (p.lat in -90.0..90.0 && p.lon in -180.0..180.0) map[p.key] = p
                }
            }
            val names = HashMap<String, String>()
            runCatching {
                val o = JSONObject(places.getString("names", "{}"))
                o.keys().forEach { k -> names[k] = o.getString(k) }
            }
            val d = SpendPlaceSettings()
            val cfg = SpendPlaceSettings(
                enabled = settings.getBoolean("enabled", d.enabled), explained = settings.getBoolean("explained", d.explained), prompted = settings.getBoolean("prompted", d.prompted),
                geocodeNames = settings.getBoolean("geocode", d.geocodeNames), includeInBackup = settings.getBoolean("backup", d.includeInBackup), mapPictures = settings.getBoolean("pictures", d.mapPictures),
                keepMonths = settings.getInt("months", d.keepMonths),
                captureOverrides = settings.getStringSet("capture", emptySet()).orEmpty().toSet(),
                skipOverrides = settings.getStringSet("skip", emptySet()).orEmpty().toSet()
            )
            return SpendPlaceState(map, names, cfg)
        }
    }
}
