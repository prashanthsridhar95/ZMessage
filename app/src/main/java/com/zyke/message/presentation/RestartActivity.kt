package com.zyke.message.presentation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Starts the app again in a fresh process, which is how Demo mode is entered and left: a process is wholly the real
 * app or wholly the demo, so nothing real is ever in memory while a demonstration runs. It lives in its own process
 * (`:restart`) so the one being replaced can end at once, without the new start being lost with it. It has no screen,
 * touches no store and shows nothing; `ZykeMessagesApplication` skips its set-up in this process.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            @Suppress("DEPRECATION")
            val next = intent.getParcelableExtra<Intent>(EXTRA_NEXT)
            if (next != null) startActivity(next)
        }
        finish()
        // This helper process has done its one job.
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_NEXT = "next"

        /** Whether this is the helper's process (its name ends in ":restart"). */
        fun isRestartProcess(): Boolean {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                // Older phones: the process names itself on the first line of its command line.
                runCatching { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrNull()
            }
            return name != null && name.endsWith(":restart")
        }

        /**
         * Replaces this process with a new one that opens the app's main screen. Call after the demo switch is saved.
         * Does not return normally: the calling process ends.
         */
        fun restartApp(context: Context) {
            val app = context.applicationContext
            val main = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val helper = Intent(app, RestartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(EXTRA_NEXT, main)
            app.startActivity(helper)
            (context as? Activity)?.finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
