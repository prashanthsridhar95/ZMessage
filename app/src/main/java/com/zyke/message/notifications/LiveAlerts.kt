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
    private const val CODE_CHANNEL = "otp_codes"

    /** Turns the receiver on or off. It starts disabled, so a phone that never opted in never runs this code. */
    fun setEnabled(context: Context, enabled: Boolean) {
        // Demo mode must not change the phone's real component settings.
        if (com.zyke.message.data.demo.DemoMode.active) return
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, SmsReceiver::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }

    /**
     * The receiver is wanted for the code and bill buttons AND for passing codes on to trusted people; it is on if either is.
     * Use this, not [setEnabled], when one of the two switches changes.
     */
    fun refreshReceiver(context: Context) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        val container = (context.applicationContext as com.zyke.message.ZykeMessagesApplication).container
        reconcile(context, container.settings.settings.value.liveAlerts || container.codeSharing.rules.value.enabled)
    }

    fun canNotify(context: Context): Boolean =
        !com.zyke.message.data.demo.DemoMode.active && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Puts the receiver's enabled state in step with the saved switch (a restored backup or an update can leave them apart). */
    fun reconcile(context: Context, liveAlerts: Boolean) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        val component = ComponentName(context, SmsReceiver::class.java)
        val on = context.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (on != liveAlerts) setEnabled(context, liveAlerts)
    }

    /** The channel code alerts are posted on, for opening its system settings page. */
    const val CODE_CHANNEL_ID = CODE_CHANNEL

    /** What the phone allows right now, for [com.zyke.message.domain.device.CodeAlertHealth]. */
    fun facts(context: Context, switchedOn: Boolean, readingPaused: Boolean): com.zyke.message.domain.device.CodeAlertFacts {
        val manager = androidx.core.app.NotificationManagerCompat.from(context)
        val postPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.getNotificationChannel(CODE_CHANNEL) else null
        return com.zyke.message.domain.device.CodeAlertFacts(
            switchedOn = switchedOn,
            readingPaused = readingPaused,
            canHearTexts = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
            notificationsAllowed = postPermission && manager.areNotificationsEnabled(),
            channelOff = channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
        )
    }

    /**
     * A made-up code through the same path a real one takes, so the person can see what an alert looks like and
     * whether it arrives. Returns false when nothing could be posted (notifications blocked, or Demo mode).
     */
    fun postTest(context: Context, classifier: com.zyke.message.domain.classification.MessageClassifier): Boolean {
        if (!canNotify(context) || !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val code = (100000..999999).random()
        val sample = com.zyke.message.domain.model.SmsMessage(0L, "ZYKE", "$code is your OTP for the Zyke Message test. Do not share it with anyone.", System.currentTimeMillis(), isRead = false)
        return runCatching { post(context, classifier.classify(sample)) }.isSuccess
    }

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
        // The sender's name alone: no check mark in a notification (a registered header shows as its name, "HDFC Bank").
        val from = com.zyke.message.domain.conversation.SenderDisplay.of(message.message.sender, message.merchant.name.takeIf { message.merchant.renamed }, content.senderName).name
        val forPayment = content.paymentAmount?.let { " for ${if (NotificationPrivacy.hidden(context)) "an amount" else DisplayFormatters.rupees(it)}" }.orEmpty()
        notify(
            context, id, CODE_CHANNEL,
            title = spaced,
            text = "From $from$forPayment · tap to copy",
            bigText = "From $from$forPayment.\nTap to copy the code, then paste it where it is asked for.\nNever share it with anyone.",
            publicTitle = "One-time code",
            publicText = "Unlock to see it",
            action = "Copy code" to copy,
            secondary = null,
            contentIntent = copy,
            // The code itself is drawn large, with the rest in small type beneath.
            customView = android.widget.RemoteViews(context.packageName, R.layout.notification_code).apply {
                setTextViewText(R.id.code_value, spaced)
                setTextViewText(R.id.code_detail, "From $from$forPayment · tap to copy")
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
            context, id, CHANNEL,
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
        channel: String,
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
        ensureChannels(context)
        val accent = androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.baseline_message_24)
            .setColor(accent)
            .setContentTitle(NotificationPrivacy.text(context, title))
            .setContentText(NotificationPrivacy.text(context, text))
            .setStyle(if (customView != null) NotificationCompat.DecoratedCustomViewStyle() else NotificationCompat.BigTextStyle().bigText(NotificationPrivacy.text(context, bigText)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (channel == CODE_CHANNEL) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // What the lock screen shows: the code or amount itself stays hidden.
            .setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.baseline_message_24).setColor(accent)
                    .setContentTitle(publicTitle).setContentText(publicText).build()
            )
            .setTimeoutAfter(TIMEOUT_MS)
        customView?.let { builder.setCustomContentView(it).setCustomBigContentView(it) }
        action?.let { (label, intent) -> builder.addAction(0, label, intent) }
        secondary?.let { (label, intent) -> builder.addAction(0, label, intent) }
        manager.notify(id, builder.build())
    }

    /** Creates the channels (harmless to repeat; the person's own choices for them are kept). */
    fun ensureChannels(context: Context) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // A code is time-critical: it peeks onto the screen. The bill button is calmer.
            manager.createNotificationChannel(
                NotificationChannel(CODE_CHANNEL, "One-time codes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A Copy code button the moment a one-time code arrives"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Code and bill buttons", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "One-tap Pay button for new card bills"
                }
            )
        }
    }

    private fun idFor(message: ClassifiedMessage): Int = (message.message.date xor message.message.body.hashCode().toLong()).toInt() and Int.MAX_VALUE

    private const val TIMEOUT_MS = 30L * 60 * 1000
}
