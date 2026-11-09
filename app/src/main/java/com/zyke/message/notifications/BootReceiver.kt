package com.zyke.message.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zyke.message.ZykeMessagesApplication

/** Alarms are forgotten when the phone restarts; this puts the morning digest, the reminders and the next flight's and train's alarm back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Demo mode sets no alarms and schedules no backups (the real app does, on its next start).
        if (com.zyke.message.data.demo.DemoMode.active) return
        val container = (context.applicationContext as ZykeMessagesApplication).container
        if (container.settings.settings.value.morningDigest) DigestScheduler(context).apply(true)
        if (container.settings.settings.value.weeklySummary) WeeklyScheduler(context).apply(true)
        if (container.watchlist.watch.value.closeSummaryActive) MarketCloseScheduler(context).apply(true)
        ReminderAlarms.schedule(context)
        // The alarm for the next flight (it only sets an alarm; the service is never started from here).
        runCatching { FlightTrackingControl.rearm(context) }
        runCatching { TrainTrackingControl.rearm(context) }
        runCatching { container.backup.scheduler.ensureScheduled() }
    }
}
