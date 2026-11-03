package com.zyke.message.data.diag

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The black box: a very small record that is ALWAYS kept, whether or not developer mode is on, so an app that
 * crashes while starting can still be diagnosed.
 *
 * What it holds: when each run started and which screens opened (class names), the step markers of start-up, an
 * uncaught exception's class names and stack frames (method names and line numbers), and how earlier runs ended
 * according to Android (reason, status, importance, time, the first frames of a trace). Never message text, amounts,
 * account numbers or names: an exception's own message is NOT written, because it can contain such text. The one exception is the
 * fixed phrase 'duplicate list key of kind <letters>' (see [safeNote]), which is all a repeated list key needs to be found.
 *
 * It lives in files/dev_logs/blackbox.log (about 256 KB, one earlier file kept), stays on the phone, is never sent by
 * the app, and is shared only when the person chooses "Share logs". Writing is a small synchronous append that can
 * never throw, and it touches nothing but its own file: no stores, no preferences, no container.
 */
object BlackBox {
    const val FILE = "blackbox.log"
    const val PREVIOUS = "blackbox.1.log"
    private const val DIR = "dev_logs"
    private const val EXIT_MARK = "blackbox.exit"
    private const val MAX_BYTES = 256L * 1024L
    private const val EXIT_HISTORY = 6
    private const val TRACE_LINES = 14
    private const val FRAMES = 40
    private const val MAX_LINE = 400

    private val lock = Any()
    @Volatile private var dir: File? = null
    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }

    /** First thing in Application.onCreate: remembers where to write, installs the crash handler, notes this start. */
    fun install(app: Application) {
        runCatching {
            dir = File(app.filesDir, DIR).apply { mkdirs() }
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous !is Handler) Thread.setDefaultUncaughtExceptionHandler(Handler(previous))
            mark("lifecycle", "process start · ${describePhone(app)}")
            app.registerActivityLifecycleCallbacks(Screens)
        }
        // Reading Android's exit history can take a moment; do it off the start-up path.
        Thread({ recordEarlierExits(app) }, "blackbox-exits").apply { isDaemon = true }.start()
    }

    /** A step marker, for example `mark("firstrun", "restore check done")`. Text must be ids and step names only. */
    fun mark(tag: String, step: String) = write("I", tag, step)

    fun phoneLine(app: Context): String = describePhone(app)

    private fun describePhone(app: Context): String {
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        return "app ${info?.versionName} (${info?.longVersionCode}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun write(level: String, tag: String, text: String) {
        runCatching {
            val folder = dir ?: return
            val line = "${stamp.get()!!.format(Date())} $level/$tag ${text.replace('\n', ' ').take(MAX_LINE)}\n"
            appendRaw(folder, line)
        }
    }

    private fun appendRaw(folder: File, text: String) {
        synchronized(lock) {
            runCatching {
                val file = File(folder, FILE)
                if (file.length() > MAX_BYTES) {
                    File(folder, PREVIOUS).delete()
                    file.renameTo(File(folder, PREVIOUS))
                }
                file.appendText(text)
            }
        }
    }

    /** An exception as class names and frames only (no message text), following its causes. */
    internal fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            append(if (depth == 0) "" else "Caused by: ").append(current.javaClass.name)
            safeNote(current)?.let { append(" [").append(it).append(']') }
            append('\n')
            current.stackTrace.take(FRAMES).forEach { append("    at ").append(it).append('\n') }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }

    /**
     * The one thing an exception's own message may contribute, and only as a fixed phrase: that a lazy list was given the
     * same key twice, with the first letters of the key's kind (never digits or names). Anything else is not written.
     */
    private fun safeNote(error: Throwable): String? {
        val text = error.message ?: return null
        if (!text.contains("was already used")) return null
        val kind = text.substringAfter("Key ", "").trimStart('"', '\'').takeWhile { it.isLetter() || it == '_' }.take(14)
        return "duplicate list key" + if (kind.isNotEmpty()) " of kind $kind" else ""
    }

    private class Handler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                val folder = dir
                if (folder != null) {
                    appendRaw(folder, "${stamp.get()!!.format(Date())} E/crash [${thread.name}] uncaught exception\n${describe(error)}")
                }
            }
            runCatching { com.zyke.message.data.backup.FirstRun.onCrash() }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Screens opening and closing, as class names: the lead-up to a crash. */
    private object Screens : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = mark("lifecycle", "${activity.javaClass.simpleName} created")
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = mark("lifecycle", "${activity.javaClass.simpleName} resumed")
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = mark("lifecycle", "${activity.javaClass.simpleName} destroyed")
    }

    /**
     * How Android says earlier runs ended. Only the ones newer than the last one already written are added, so a
     * crash loop does not fill the file with copies. The last time is kept in a tiny file beside the log.
     */
    private fun recordEarlierExits(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val folder = dir ?: return
            val markFile = File(folder, EXIT_MARK)
            val last = runCatching { markFile.readText().trim().toLong() }.getOrDefault(0L)
            val manager = app.getSystemService(android.app.ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(app.packageName, 0, EXIT_HISTORY)
                .filter { it.timestamp > last }.sortedBy { it.timestamp }
            exits.forEach { exit ->
                write(
                    "I", "exit",
                    "${stamp.get()!!.format(Date(exit.timestamp))} ended: ${reasonName(exit.reason)} · status=${exit.status} · importance=${exit.importance} · " +
                        "pss=${exit.pss / 1024}MB · ${exit.description.orEmpty().take(160)}"
                )
                if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR || exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    runCatching { exit.traceInputStream?.bufferedReader()?.useLines { lines -> lines.take(TRACE_LINES).joinToString(" | ") } }
                        .getOrNull()?.takeIf { it.isNotBlank() }?.let { write("I", "exit-trace", it) }
                }
            }
            exits.maxOfOrNull { it.timestamp }?.let { markFile.writeText(it.toString()) }
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        else -> "OTHER($reason)"
    }
}
