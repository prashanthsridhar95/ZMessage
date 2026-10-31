package com.zyke.message

import android.app.Application
import com.zyke.message.di.AppContainer

class ZykeMessagesApplication : Application() {
    lateinit var container: AppContainer
        private set

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
        // First, so everything after it can be recorded when developer mode is on.
        com.zyke.message.data.diag.DevLog.init(this)
        container = AppContainer(this)
        com.zyke.message.data.diag.DevLog.d("app") { "container ready" }
    }
}
