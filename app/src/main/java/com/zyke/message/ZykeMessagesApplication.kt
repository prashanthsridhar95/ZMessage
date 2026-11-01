package com.zyke.message

import android.app.Application
import com.zyke.message.data.diag.BlackBox
import com.zyke.message.di.AppContainer

class ZykeMessagesApplication : Application() {
    /**
     * Built on first use rather than in [onCreate], so a failure while building it takes down the screen that asked,
     * not the whole process: the "Share logs" shortcut (a separate screen that never asks for it) keeps working.
     */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.zyke.message.data.diag.DevLog.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.zyke.message.data.diag.DevLog.d("memory") { "the whole phone is low on memory" }
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything else, so even a crash while the rest starts is recorded (black box; independent of developer mode).
        BlackBox.install(this)
        // Then the verbose developer log, which writes only when developer mode is on.
        runCatching { com.zyke.message.data.diag.DevLog.init(this) }.onFailure { BlackBox.mark("lifecycle", "developer log could not start: ${it.javaClass.simpleName}") }
        BlackBox.mark("lifecycle", "building the container")
        runCatching { container }.onFailure { BlackBox.mark("lifecycle", "container failed: ${it.javaClass.simpleName}") }
        // The stores are built on first use; start building them now, in parallel and off the main thread, so the
        // first frame is not what pays for reading each one's saved data.
        runCatching { container.warmUp() }
        BlackBox.mark("lifecycle", "start-up done")
        com.zyke.message.data.diag.DevLog.d("app") { "container ready" }
    }
}
