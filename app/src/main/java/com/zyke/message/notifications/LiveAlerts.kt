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
        // The code is the headline, large and spaced so it can be read at a glance and typed from the shade.
        val spaced = if (content.code.length == 6) "${content.code.take(3)} ${content.code.drop(3)}" else content.code
        val forPayment = content.paymentAmount?.let { " for ${DisplayFormatters.rupees(it)}" }.orEmpty()
        notify(
            context, id,
            title = spaced,
            text = "From ${content.senderName}$forPayment · tap to copy",
            bigText = "From ${content.senderName}$forPayment.\nTap to copy the code, then paste it where it is asked for.\nNever share it with anyone.",
            publicTitle = "One-time code",
            publicText = "Unlock to see it",
            action = "Copy code" to copy,
            secondary = null,
            contentIntent = copy,
            // The code itself is drawn large, with the rest in small type beneath.
            customView = android.widget.RemoteViews(context.packageName, R.layout.notification_code).apply {
                setTextViewText(R.id.code_value, spaced)
                setTextViewText(R.id.code_detail, "From ${content.senderName}$forPayment\nTap to copy. Never share it.")
            }
        )
    }

    private fun postBill(context: Context, message: ClassifiedMessage, content: MessageContent.CardBill) {
        val id = idFor(message)
        val link = content.paymentLink
        val due = content.dueDate?.let { DisplayFormatters.dueLabel(it).lowercase() }
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
        val minimum = content.minimumDue?.let { "Minimum due ${DisplayFormatters.rupees(it)}" }
        notify(
            context, id,
            title = DisplayFormatters.rupees(content.amount) + (due?.let { " · $it" } ?: ""),
            text = "${content.bankName.displayString} card bill",
            bigText = listOfNotNull("${content.bankName.displayString} card bill", minimum, due?.replaceFirstChar { it.uppercase() }).joinToString("\n"),
            publicTitle = "Card bill",
            publicText = "Unlock to see the amount",
            action = pay?.let { "Pay" to it },
            secondary = "Open" to open,
            contentIntent = open
        )
    }

    private fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        bigText: String,
        publicTitle: String,
        publicText: String,
        action: Pair<String, PendingIntent>?,
        secondary: Pair<String, PendingIntent>?,
        contentIntent: PendingIntent,
        customView: android.widget.RemoteViews? = null
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Code and bill buttons", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One-tap Copy code and Pay buttons for new codes and card bills"
            }
        )
        val accent = androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.baseline_message_24)
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(if (customView != null) NotificationCompat.DecoratedCustomViewStyle() else NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // What the lock screen shows: the code or amount itself stays hidden.
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.baseline_message_24).setColor(accent)
                    .setContentTitle(publicTitle).setContentText(publicText).build()
            )
            .setTimeoutAfter(TIMEOUT_MS)
        customView?.let { builder.setCustomContentView(it).setCustomBigContentView(it) }
        action?.let { (label, intent) -> builder.addAction(0, label, intent) }
        secondary?.let { (label, intent) -> builder.addAction(0, label, intent) }
        manager.notify(id, builder.build())
    }

    private fun idFor(message: ClassifiedMessage): Int = (message.message.date xor message.message.body.hashCode().toLong()).toInt() and Int.MAX_VALUE

    private const val TIMEOUT_MS = 30L * 60 * 1000
}
