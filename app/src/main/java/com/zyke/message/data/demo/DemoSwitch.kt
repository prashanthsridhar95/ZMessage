package com.zyke.message.data.demo

import android.app.NotificationManager
import android.content.Context
import com.zyke.message.presentation.RestartActivity

/**
 * Turns Demo mode on or off. Both save the switch, clear the notifications the app has posted (so nothing real stays in
 * the shade during a demonstration, and nothing made-up stays after it), and restart the app in a new process.
 *
 * Entering does not read or touch the person's data in any way: the real stores are simply never opened by the new
 * process. Leaving deletes the demo's scratch folder; the real data is exactly as it was, since nothing wrote to it.
 */
object DemoSwitch {
    fun isOn(context: Context): Boolean = DemoMode.isSaved(context)

    fun enter(context: Context) {
        if (!DemoMode.save(context, true)) return
        clearNotifications(context)
        RestartActivity.restartApp(context)
    }

    fun exit(context: Context) {
        if (!DemoMode.save(context, false)) return
        DemoMode.cleanUp(context)
        clearNotifications(context)
        RestartActivity.restartApp(context)
    }

    private fun clearNotifications(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancelAll() }
    }
}
