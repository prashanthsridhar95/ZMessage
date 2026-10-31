package com.zyke.message.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.usecase.LoadProgress
import com.zyke.message.presentation.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while a very large inbox is still being read, month by month, so leaving the app does not
 * stop the work. It does the reading by listening to the same shared stream the screens use, shows how far it has
 * got, and stops by itself the moment every month has been read. Nothing leaves the phone.
 */
class BackfillService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as ZykeMessagesApplication).container
        com.zyke.message.data.diag.DevLog.d("service") { "BackfillService started: ${container.observeMessages.progress.value}" }
        startInForeground(notification(this, container.observeMessages.progress.value))

        // Listening is what keeps the shared reading going when no screen is.
        scope.launch { container.observeMessages().collect { } }
        scope.launch {
            container.observeMessages.progress.collect { progress ->
                if (progress.isDone && progress.monthsTotal > 0) {
                    finish()
                } else {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(this@BackfillService, progress))
                }
            }
        }
        // An empty inbox never reports months; do not linger.
        scope.launch {
            delay(NOTHING_TO_READ_MS)
            if (container.observeMessages.progress.value.monthsTotal == 0) finish()
        }
        return START_NOT_STICKY
    }

    /** Everything is read: take the notification down at once, and make sure no late update can bring it back. */
    private fun finish() {
        com.zyke.message.data.diag.DevLog.d("service") { "BackfillService finishing (already=$finished)" }
        if (finished) return
        finished = true
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
        stopSelf()
    }

    override fun onDestroy() {
        finished = true
        scope.cancel()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "backfill"
        private const val ID = 4107
        private const val NOTHING_TO_READ_MS = 30_000L
        /** A phone with fewer messages than this reads them in moments, so no service is needed. */
        const val BIG_INBOX = 20_000

        /** A visit that finds this many new messages waiting reads them in the background too. */
        const val MANY_NEW = 300

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BackfillService::class.java))
        }

        private fun notification(context: Context, progress: LoadProgress): Notification {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reading older messages", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a large inbox is read month by month"
                }
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.baseline_message_24)
                .setContentTitle(if (progress.isFirstSync) "Getting your messages ready" else "Reading your messages")
                .setContentText(describe(progress))
                .setProgress(progress.monthsTotal.coerceAtLeast(1), progress.monthsLoaded, progress.monthsTotal == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()
        }

        /** "12 of 34 months · back to Mar 2025". */
        fun describe(progress: LoadProgress): String {
            if (progress.monthsTotal == 0) return "Getting started"
            val back = progress.oldestLoaded?.let {
                DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
            }
            return "${progress.monthsLoaded} of ${progress.monthsTotal} months" + (back?.let { " · back to $it" } ?: "")
        }
    }
}
