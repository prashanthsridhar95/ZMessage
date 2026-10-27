package com.zyke.message.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

/** The "Copy code" button on a code notification: puts the code on the clipboard and clears the notification. */
class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveAlerts.ACTION_COPY) return
        val code = intent.getStringExtra(LiveAlerts.EXTRA_CODE) ?: return

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("One-time code", code).apply {
            // Keeps the code out of the clipboard preview the keyboard and system show.
            description.extras = PersistableBundle().apply {
                putBoolean(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE
                    else "android.content.extra.IS_SENSITIVE",
                    true
                )
            }
        }
        clipboard.setPrimaryClip(clip)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(intent.getIntExtra(LiveAlerts.EXTRA_NOTIFICATION_ID, 0))
    }
}
