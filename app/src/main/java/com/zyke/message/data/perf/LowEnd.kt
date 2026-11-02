package com.zyke.message.data.perf

import android.app.ActivityManager
import android.content.Context
import com.zyke.message.domain.model.LowEndRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore

/**
 * Whether this is a low-end phone: Android's own low-RAM flag, about 3 GB of memory or less, or a small per-app heap
 * (see [LowEndRules]; the numbers are read here, the decision is made there, where it is tested). The answer is cached
 * in a tiny preference file, so the next start knows it at once; the first start works it out on a background thread.
 */
object LowEnd {
    private const val PREFS = "perf"
    private const val KEY_LOW_END = "low_end"
    private const val KEY_KNOWN = "known"

    private val _detected = MutableStateFlow(false)
    /** True once the phone was found to be low-end. False until known, so the first frame is the full experience. */
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    /** Called once from Application.onCreate. Cheap on the main thread: one small preference read, then a thread. */
    fun init(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_KNOWN, false)) _detected.value = prefs.getBoolean(KEY_LOW_END, false)
        Thread({
            val found = runCatching { detect(app) }.getOrDefault(false)
            prefs.edit().putBoolean(KEY_LOW_END, found).putBoolean(KEY_KNOWN, true).apply()
            _detected.value = found
        }, "low-end-detect").apply { isDaemon = true }.start()
    }

    /** Reads the system's numbers and applies [LowEndRules]. Safe to call from any thread. */
    fun detect(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        return LowEndRules.isLowEnd(manager.isLowRamDevice, info.totalMem, manager.memoryClass)
    }
}

/**
 * What lite mode changes outside the screens (the screens read LocalLite instead). Set whenever the person's
 * performance choice or the detection changes; read by the pipelines and the logger when they start their next piece of work.
 */
object LiteRuntime {
    private const val PARALLEL_PARTS_LITE = 2
    private const val CALM_GAP_MS_LITE = 1_200L
    private const val CALM_GAP_MS_FULL = 700L
    private const val MEMORY_EVERY_S_LITE = 15L
    private const val MEMORY_EVERY_S_FULL = 5L

    @Volatile
    private var on = false

    /** Whether lite mode is on right now. */
    val isLite: Boolean get() = on

    fun apply(lite: Boolean) {
        on = lite
    }

    /** At most this many derive parts run at once in lite mode, so four weak cores and little memory are not all taken at once. */
    private val gate = Semaphore(PARALLEL_PARTS_LITE)

    /** Runs one derive part: freely on a normal phone, two at a time in lite mode. */
    suspend fun <T> bounded(work: () -> T): T = if (on) {
        gate.acquire()
        try { work() } finally { gate.release() }
    } else {
        work()
    }

    /** The least time between two rebuilds of a screen while the inbox is still being read. */
    fun calmGapMs(): Long = if (on) CALM_GAP_MS_LITE else CALM_GAP_MS_FULL

    /** Seconds between memory notes in the developer log. */
    fun memoryEverySeconds(): Long = if (on) MEMORY_EVERY_S_LITE else MEMORY_EVERY_S_FULL
}
