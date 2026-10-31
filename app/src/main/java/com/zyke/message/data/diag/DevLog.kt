package com.zyke.message.data.diag

import android.content.Context
import android.os.Build
import com.zyke.message.domain.diag.DiagLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The developer log: a file on the phone that records what the app is doing, for finding out why something does not
 * work. It exists only after the person turns on developer mode (tap the version in Settings seven times), and writing
 * to it is a switch they can turn off.
 *
 * What is written: when things happen, ids, counts, kinds and timings. Never message text, amounts, account numbers or
 * names; a sender or receiver key is written as a short fingerprint (see [DiagLog.key]). The file stays in the app's
 * private storage until the person chooses to share it, and it is trimmed to a size limit.
 */
object DevLog {
    private const val PREFS = "developer"
    private const val DEVELOPER = "developer_mode"
    private const val LOGGING = "logging"
    private const val MAX_BYTES = 1_500_000L
    private const val TAIL_LINES = 150
    private const val EXIT_HISTORY = 6
    private const val EXIT_TRACE_CHARS = 8_000
    private const val FLUSH_WAIT_MS = 800L

    private val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "dev-log").apply { isDaemon = true } }
    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }

    private var directory: File? = null
    private var prefs: android.content.SharedPreferences? = null

    private val _developerMode = MutableStateFlow(false)
    /** Whether the person has unlocked developer options. */
    val developerMode: StateFlow<Boolean> = _developerMode.asStateFlow()

    private val _logging = MutableStateFlow(false)
    /** Whether lines are being written to the file now. */
    val logging: StateFlow<Boolean> = _logging.asStateFlow()

    /** Called once, first thing, when the app starts. */
    fun init(context: Context) {
        val app = context.applicationContext
        directory = File(app.filesDir, "dev_logs").apply { mkdirs() }
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _developerMode.value = prefs?.getBoolean(DEVELOPER, false) == true
        _logging.value = _developerMode.value && prefs?.getBoolean(LOGGING, true) == true
        hook()
        if (_logging.value) {
            installCrashHandler()
            sessionHeader(app)
        }
    }

    fun setDeveloperMode(context: Context, on: Boolean) {
        prefs?.edit()?.putBoolean(DEVELOPER, on)?.apply()
        _developerMode.value = on
        if (on) setLogging(context, true) else setLogging(context, false)
    }

    fun setLogging(context: Context, on: Boolean) {
        prefs?.edit()?.putBoolean(LOGGING, on)?.apply()
        val was = _logging.value
        _logging.value = on && _developerMode.value
        hook()
        if (_logging.value && !was) {
            installCrashHandler()
            sessionHeader(context.applicationContext)
        }
    }

    private fun hook() {
        DiagLog.sink = if (_logging.value) { tag, message -> write("D", tag, message) } else null
    }

    fun d(tag: String, message: () -> String) {
        if (!_logging.value) return
        write("D", tag, runCatching(message).getOrElse { "(could not build log line: ${it.javaClass.simpleName})" })
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (!_logging.value) return
        write("E", tag, message + (error?.let { " | ${it.javaClass.name}: ${it.message}\n" + it.stackTraceToString().take(4000) } ?: ""))
    }

    private fun write(level: String, tag: String, message: String) {
        val line = "${stamp.get()!!.format(Date())} $level/$tag [${Thread.currentThread().name}] $message\n"
        writer.execute {
            runCatching {
                val file = current() ?: return@execute
                if (file.length() > MAX_BYTES) rotate()
                file.appendText(line)
            }
        }
    }

    private fun current(): File? = directory?.let { File(it, "zyke.log") }

    private fun rotate() {
        val dir = directory ?: return
        File(dir, "zyke.1.log").delete()
        File(dir, "zyke.log").renameTo(File(dir, "zyke.1.log"))
    }

    private fun sessionHeader(app: Context) {
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        write(
            "I", "session",
            "app ${info?.versionName} (${info?.longVersionCode}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
        )
        recordHowEarlierRunsEnded(app)
    }

    /**
     * Android keeps a short history of how this app's earlier runs ended, including what the app itself cannot write
     * down: a crash in native code, an app-not-responding, being stopped for memory, being killed by the system. It is
     * copied into the log here, so such an ending shows up even though the app was not alive to record it.
     */
    private fun recordHowEarlierRunsEnded(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            write("I", "exit", "this Android version does not keep a history of how the app ended")
            return
        }
        runCatching {
            val manager = app.getSystemService(android.app.ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(app.packageName, 0, EXIT_HISTORY)
            if (exits.isEmpty()) write("I", "exit", "no earlier run ended abnormally on record")
            exits.forEach { exit ->
                val reason = when (exit.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH (an uncaught exception)"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (a crash in native code)"
                    android.app.ApplicationExitInfo.REASON_ANR -> "ANR (the app stopped responding)"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (stopped by the system for memory)"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE (failed while starting)"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by a signal)"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force stopped)"
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                    android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                    android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE (a permission was changed, which restarts the app)"
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    else -> "OTHER(${exit.reason})"
                }
                write(
                    "I", "exit",
                    "${stamp.get()!!.format(Date(exit.timestamp))} ended: $reason · status=${exit.status} · importance=${exit.importance} · memory(pss)=${exit.pss / 1024}MB · ${exit.description.orEmpty()}"
                )
                // A native crash or an app-not-responding comes with the stack of what the app was doing.
                if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR || exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    runCatching { exit.traceInputStream?.use { it.readBytes().toString(Charsets.UTF_8).take(EXIT_TRACE_CHARS) } }
                        .getOrNull()?.takeIf { it.isNotBlank() }?.let { write("I", "exit-trace", "\n$it") }
                }
            }
        }.onFailure { write("E", "exit", "could not read how earlier runs ended: ${it.javaClass.simpleName}") }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(previous))
    }

    private class CrashLogger(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                // First let the lines already queued reach the file, so the lead-up to the crash is not lost.
                runCatching { writer.submit { }.get(FLUSH_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                // Then the crash itself, straight away: the process is about to end.
                current()?.appendText("${stamp.get()!!.format(Date())} E/crash [${thread.name}] ${error.javaClass.name}: ${error.message}\n${error.stackTraceToString().take(6000)}\n")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The log as text, oldest line first, the earlier file first if there is one. */
    fun readAll(): String {
        val dir = directory ?: return ""
        return listOf(File(dir, "zyke.1.log"), File(dir, "zyke.log")).filter { it.exists() }.joinToString("") { it.readText() }
    }

    /** The newest lines, for showing on screen. */
    fun tail(lines: Int = TAIL_LINES): String {
        val file = current()?.takeIf { it.exists() } ?: return ""
        return runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
    }

    fun sizeBytes(): Long = directory?.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        directory?.listFiles()?.forEach { it.delete() }
    }
}
