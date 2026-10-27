package com.zyke.message

import android.app.Application
import com.zyke.message.di.AppContainer

class ZykeMessagesApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
