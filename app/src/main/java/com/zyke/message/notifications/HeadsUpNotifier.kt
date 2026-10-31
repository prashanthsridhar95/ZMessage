package com.zyke.message.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.insights.HeadsUp
import com.zyke.message.domain.insights.HeadsUps
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.presentation.MainActivity
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/** Quiet notes for a payment that just arrived: far above the person's usual, or leaving an account below their floor. */
internal object HeadsUpNotifier {
    private const val CHANNEL = "heads_up"

    /** Only runs from the live-alert path, so it needs live alerts on as well as its own switch. */
    suspend fun check(context: Context, message: ClassifiedMessage) {
        val container = (context.applicationContext as ZykeMessagesApplication).container
        val settings = container.settings.settings.value
        if (!settings.liveAlerts || !settings.headsUp || settings.readingPaused || !LiveAlerts.canNotify(context)) return
        val history = runCatching { container.observeMessages().first() }.getOrNull() ?: return
        val found = HeadsUps.evaluate(message, history, settings.lowBalanceFloor, ZoneId.systemDefault(), System.currentTimeMillis())
        com.zyke.message.data.diag.DevLog.d("receiver") { "heads-up checked: ${found.size} found" }
        found.forEach { post(context, message, it) }
    }

    private fun post(context: Context, message: ClassifiedMessage, headsUp: HeadsUp) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Heads-up alerts", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note when a payment is far above usual or leaves an account low"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
        val id = (message.message.date xor headsUp.kind.ordinal.toLong() xor message.message.body.hashCode().toLong()).toInt() and Int.MAX_VALUE
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val accent = ContextCompat.getColor(context, R.color.zyke_accent)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.baseline_message_24)
            .setColor(accent)
            .setContentTitle(headsUp.title)
            .setContentText(headsUp.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(headsUp.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // The amount stays off the lock screen.
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.baseline_message_24).setColor(accent)
                    .setContentTitle("Heads-up").setContentText("Unlock to see it").build()
            )
            .build()
        manager.notify(id, notification)
    }
}
