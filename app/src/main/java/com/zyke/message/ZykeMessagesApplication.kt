package com.zyke.message

import android.app.Application

class ZykeMessagesApplication: Application() {
    override fun onCreate() {
        super.onCreate()
    }

    companion object
    {
        lateinit var INSTANCE: ZykeMessagesApplication
        fun getInstance(): ZykeMessagesApplication
        {
            if(::INSTANCE.isInitialized.not())
            {
                INSTANCE = ZykeMessagesApplication()
            }
            return INSTANCE
        }
    }
}