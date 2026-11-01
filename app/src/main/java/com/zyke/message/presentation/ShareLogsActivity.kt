package com.zyke.message.presentation

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.zyke.message.data.diag.LogShare

/**
 * The "Share logs" shortcut on the launcher icon (long-press). It has no screen: it hands the log files to the share
 * sheet and closes. It must keep working when the app crashes on launch, so it uses a plain Activity and touches none
 * of the app's container, view models, preference stores or Compose; everything is wrapped so it cannot crash.
 */
class ShareLogsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when (LogShare.share(this)) {
                LogShare.Result.SHARED -> Unit
                LogShare.Result.NOTHING_YET -> Toast.makeText(applicationContext, "No logs yet", Toast.LENGTH_LONG).show()
                LogShare.Result.FAILED -> Toast.makeText(applicationContext, "Could not share the logs", Toast.LENGTH_LONG).show()
            }
        } catch (_: Throwable) {
        }
        finish()
    }
}
