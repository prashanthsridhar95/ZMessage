package com.zyke.message.notifications

import android.Manifest
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.domain.insights.DigestItem
import android.text.style.StyleSpan
import android.text.SpannableString
import android.graphics.Typeface
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyke.message.R
import com.zyke.message.domain.insights.DigestSummary
import com.zyke.message.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Posts the morning digest. It reads the inbox on the phone and shows one notification; nothing is sent anywhere. */
class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Tomorrow's alarm first, so a failure below cannot end the mornings.
                val container = (context.applicationContext as com.zyke.message.ZykeMessagesApplication).container
                if (!container.settings.settings.value.morningDigest) return@launch
                DigestScheduler(context).apply(true)
                DigestNotifier.show(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Builds the digest and puts it in the notification shade, where it stays until it is read or swiped away. */
object DigestNotifier {
    private const val CHANNEL = "digest_morning"
    private const val NOTIFICATION_ID = 802

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** A quiet day still gets its line, so a missing notification means something is wrong, not that nothing is due. */
    suspend fun show(context: Context): Boolean {
        if (!canNotify(context)) return false
        val digest = DigestProvider.load(context) ?: DigestSummary("Your day", emptyList())
        post(context, digest)
        return true
    }

    private fun post(context: Context, digest: DigestSummary) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Shown as a banner when it arrives and waits in the shade, readable once the phone is unlocked.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Morning digest", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "One short summary each morning of what is due and what is on"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(LocalDate.now())
        val items = digest.items
        val urgent = items.count { it.urgent }

        // Collapsed: how many things, and the most pressing one. Expanded: every thing on its own line.
        val headline = when {
            items.isEmpty() -> "All clear"
            items.size == 1 -> "1 thing today"
            else -> "${items.size} things today" + if (urgent > 0) " · $urgent due now" else ""
        }
        val preview = items.firstOrNull()?.let { line(it) } ?: "Nothing due or on today"
        val style = NotificationCompat.InboxStyle().setSummaryText("Zyke Message")
        if (items.isEmpty()) style.addLine("Nothing due or on today") else items.forEach { style.addLine(boldTitle(it)) }

        val accent = ContextCompat.getColor(context, R.color.zyke_accent)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.baseline_message_24)
            .setColor(accent)
            .setContentTitle("$headline · $day")
            .setContentText(preview)
            .setStyle(style)
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(false)
            .setAutoCancel(true)
            // The text stays off the lock screen, with a plain line in its place, until the phone is unlocked.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.baseline_message_24)
                    .setColor(accent)
                    .setContentTitle("Your day")
                    .setContentText("Unlock to read your morning summary")
                    .build()
            )
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** "Reminder: Rent ₹12,000 today" as one plain line. */
    private fun line(item: DigestItem): String =
        listOfNotNull(item.title, item.amount?.let { DisplayFormatters.rupees(it) }, item.detail.takeIf { it.isNotBlank() }).joinToString(" · ")

    /** The same line with the title in bold, so the eye finds what each is about. */
    private fun boldTitle(item: DigestItem): CharSequence {
        val text = line(item)
        return SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, item.title.length.coerceAtMost(text.length), 0) }
    }
}
