package com.zyke.message.presentation

import android.app.Activity
import android.os.Bundle
import com.zyke.message.data.demo.DemoSwitch

/**
 * The "Demo mode" shortcut on the launcher icon (long-press). It has no screen: it switches Demo mode on and starts the app
 * again, which then shows the made-up setup instead of the person's own data. It needs no unlock because nothing real is
 * shown in Demo mode; leaving it restarts the real app, which asks for the app lock exactly as on any other start.
 * Like the "Share logs" shortcut it is a plain Activity that touches none of the app's container, stores, view models or
 * Compose, and everything is wrapped so it cannot crash.
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (DemoSwitch.isOn(this)) {
                // Already demonstrating: just bring the app to the front.
                startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                DemoSwitch.enter(this)
            }
        } catch (_: Throwable) {
        }
        finish()
    }
}
