package com.zyke.message.domain.places

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where payments were made, and the person's choices about it. Everything stays on the phone. */
interface SpendPlaceRepository {
    val state: StateFlow<SpendPlaceState>

    fun put(place: SpendPlace)
    /** Removes one payment's place; returns it so the caller can offer Undo. */
    fun remove(key: String): SpendPlace?
    fun restore(place: SpendPlace)
    fun clearAll()
    fun setNickname(spotKey: String, name: String?)
    fun update(change: (SpendPlaceSettings) -> SpendPlaceSettings)
    /** Drops places older than the keep-for setting, and the oldest ones beyond the cap. Returns how many went. */
    fun prune(now: Long, zone: ZoneId = ZoneId.systemDefault()): Int
    /** Drops places whose message is gone: [liveKeys] are the keys of every message still on the phone. */
    fun dropOrphans(liveKeys: Set<String>): Int
}

/** The store of a demo or a build without places: holds nothing and keeps nothing. */
object NoSpendPlaces : SpendPlaceRepository {
    private val empty = MutableStateFlow(SpendPlaceState()).asStateFlow()
    override val state: StateFlow<SpendPlaceState> = empty
    override fun put(place: SpendPlace) = Unit
    override fun remove(key: String): SpendPlace? = null
    override fun restore(place: SpendPlace) = Unit
    override fun clearAll() = Unit
    override fun setNickname(spotKey: String, name: String?) = Unit
    override fun update(change: (SpendPlaceSettings) -> SpendPlaceSettings) = Unit
    override fun prune(now: Long, zone: ZoneId) = 0
    override fun dropOrphans(liveKeys: Set<String>) = 0
}

/** The real store: atomic changes, a cap of [MAX_PLACES] (oldest dropped), [persist] called after each change. */
class SpendPlaceStore(initial: SpendPlaceState = SpendPlaceState(), private val persist: (SpendPlaceState) -> Unit = {}) : SpendPlaceRepository {
    private val lock = Any()
    private val flow = MutableStateFlow(capped(initial))
    override val state: StateFlow<SpendPlaceState> = flow.asStateFlow()

    override fun put(place: SpendPlace) = change { it.copy(places = capped(it.copy(places = it.places + (place.key to place))).places) }

    override fun remove(key: String): SpendPlace? = synchronized(lock) {
        val found = flow.value.places[key] ?: return null
        change { it.copy(places = it.places - key) }
        found
    }

    override fun restore(place: SpendPlace) = put(place)

    override fun clearAll() = change { it.copy(places = emptyMap(), names = emptyMap()) }

    override fun setNickname(spotKey: String, name: String?) = change { s ->
        val clean = name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
        s.copy(names = if (clean == null) s.names - spotKey else s.names + (spotKey to clean))
    }

    override fun update(change: (SpendPlaceSettings) -> SpendPlaceSettings) = change { it.copy(settings = change(it.settings)) }

    override fun prune(now: Long, zone: ZoneId): Int = synchronized(lock) {
        val s = flow.value
        val months = s.settings.keepMonths
        val before = s.places.size
        if (months <= 0) return 0
        val cutoff = Instant.ofEpochMilli(now).atZone(zone).minusMonths(months.toLong()).toInstant().toEpochMilli()
        val kept = s.places.filterValues { it.at >= cutoff }
        if (kept.size != before) change { it.copy(places = kept) }
        before - kept.size
    }

    override fun dropOrphans(liveKeys: Set<String>): Int = synchronized(lock) {
        val s = flow.value
        if (liveKeys.isEmpty()) return 0
        // A place whose message is no longer in the inbox (deleted) goes, but only once it is old enough that the inbox has had
        // time to be read: a fresh place waits for its message to show up.
        val grace = System.currentTimeMillis() - ORPHAN_GRACE_MS
        val kept = s.places.filter { (k, p) -> k in liveKeys || p.at > grace }
        val gone = s.places.size - kept.size
        if (gone > 0) change { it.copy(places = kept) }
        gone
    }

    private fun change(apply: (SpendPlaceState) -> SpendPlaceState) = synchronized(lock) {
        val after = apply(flow.value)
        flow.value = after
        persist(after)
    }

    companion object {
        const val MAX_PLACES = 5_000
        const val MAX_NAME = 40
        const val ORPHAN_GRACE_MS = 24L * 3_600_000L

        private fun capped(s: SpendPlaceState): SpendPlaceState =
            if (s.places.size <= MAX_PLACES) s
            else s.copy(places = s.places.values.sortedByDescending { it.at }.take(MAX_PLACES).associateBy { it.key })
    }
}
