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
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(previous))
    }

    private class CrashLogger(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                // Written straight away: the process is about to end.
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
