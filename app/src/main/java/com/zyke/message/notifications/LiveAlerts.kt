package com.zyke.message.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyke.message.R
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.MainActivity
import com.zyke.message.presentation.format.DisplayFormatters

/**
 * One-tap notifications for the two kinds of message worth acting on the moment they arrive: a one-time
 * code ("Copy code") and a card bill ("Pay"). The messaging app still shows its own notification for every
 * message; these add the button, and nothing else is ever posted.
 */
internal object LiveAlerts {
    const val ACTION_COPY = "com.zyke.message.action.COPY_CODE"
    const val EXTRA_CODE = "code"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    private const val CHANNEL = "live_alerts"

    /** Turns the receiver on or off. It starts disabled, so a phone that never opted in never runs this code. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, SmsReceiver::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, message: ClassifiedMessage) {
        if (!canNotify(context)) return
        when (val content = message.content) {
            is MessageContent.Otp -> if (content.code.isNotEmpty()) postCode(context, message, content)
            is MessageContent.CardBill -> postBill(context, message, content)
            else -> Unit
        }
    }

    private fun postCode(context: Context, message: ClassifiedMessage, content: MessageContent.Otp) {
        val id = idFor(message)
        val copy = PendingIntent.getBroadcast(
            context, id,
            Intent(context, CopyCodeReceiver::class.java).setAction(ACTION_COPY)
                .putExtra(EXTRA_CODE, content.code).putExtra(EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            context, id,
            title = "Code from ${content.senderName}",
            text = content.code,
            publicText = "A new one-time code",
            action = "Copy code" to copy,
            contentIntent = copy
        )
    }

    private fun postBill(context: Context, message: ClassifiedMessage, content: MessageContent.CardBill) {
        val id = idFor(message)
        val link = content.paymentLink
        val due = content.dueDate?.let { " due ${DisplayFormatters.dueLabel(it).removePrefix("Due ").lowercase()}" }.orEmpty()
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pay = link?.let {
            PendingIntent.getActivity(
                context, id + 1, Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        notify(
            context, id,
            title = "${content.bankName.displayString} card bill",
            text = DisplayFormatters.rupees(content.amount) + due,
            publicText = "A new card bill",
            action = pay?.let { "Pay" to it },
            contentIntent = open
        )
    }

    private fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        publicText: String,
        action: Pair<String, PendingIntent>?,
        contentIntent: PendingIntent
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Code and bill buttons", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One-tap Copy code and Pay buttons for new codes and card bills"
            }
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.baseline_message_24)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // What the lock screen shows: the code or amount itself stays hidden.
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.baseline_message_24)
                    .setContentTitle(title).setContentText(publicText).build()
            )
            .setTimeoutAfter(TIMEOUT_MS)
        action?.let { (label, intent) -> builder.addAction(0, label, intent) }
        manager.notify(id, builder.build())
    }

    private fun idFor(message: ClassifiedMessage): Int = (message.message.date xor message.message.body.hashCode().toLong()).toInt() and Int.MAX_VALUE

    private const val TIMEOUT_MS = 30L * 60 * 1000
}
