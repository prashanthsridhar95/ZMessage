package com.zyke.message.data.diag

import com.zyke.message.domain.diag.ProbeResult
import com.zyke.message.domain.diag.TrainProbeFormat

/**
 * The replies of the last "Probe train sources" run, held in memory so "Share raw replies" can offer them. Same rules as
 * [LiveReplyMemo]: public replies for the sample train number only, never written to disk, never logged, gone when the app process
 * ends. Only the latest run is kept.
 */
object TrainProbeMemo {
    private class Held(val results: List<ProbeResult>, val sample: String)

    @Volatile
    private var held: Held? = null

    fun put(results: List<ProbeResult>, sample: String) {
        held = Held(results.toList(), sample)
    }

    /** The text to share (every body capped at 16 KB, labelled by candidate), or null when nothing was collected. */
    fun shareText(): String? = held?.takeIf { it.results.isNotEmpty() }?.let { TrainProbeFormat.shareText(it.results, it.sample) }

    fun clear() {
        held = null
    }
}
