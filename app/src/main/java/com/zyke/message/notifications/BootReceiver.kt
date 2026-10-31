package com.zyke.message.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zyke.message.ZykeMessagesApplication

/** Alarms are forgotten when the phone restarts; this puts the morning digest back if it is switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as ZykeMessagesApplication).container
        if (container.settings.settings.value.morningDigest) DigestScheduler(context).apply(true)
        if (container.settings.settings.value.weeklySummary) WeeklyScheduler(context).apply(true)
        ReminderAlarms.schedule(context)
    }
}
