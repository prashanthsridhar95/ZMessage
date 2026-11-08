package com.zyke.message.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.balance.BalanceRequest
import com.zyke.message.domain.balance.BalanceRequests
import com.zyke.message.domain.balance.RequestStatus

/**
 * "Check balance by SMS": texts a bank's balance-enquiry number the keyword the person confirmed, once, when they tap Send. It is
 * called from the sheet's Send button only (never from a receiver, a schedule or the inbox), at most once per account every two
 * minutes. The text itself leaves through [CodeSharer.sendPlain]; the bank's reply is an ordinary incoming message that the
 * classifier reads like any other balance message.
 *
 * Nothing here logs, saves or notifies the number or the words: the saved request holds the account key, the time and the status.
 */
internal object BalanceRequester {
    const val ACTION_STATUS = "com.zyke.message.action.BALANCE_REQUEST_STATUS"
    private const val EXTRA_KEY = "request_key"
    private const val EXTRA_AT = "request_at"

    enum class Result { QUEUED, NO_PERMISSION, TOO_SOON, FAILED }

    fun canSend(context: Context): Boolean = CodeSharer.canSend(context)

    /** Sends [text] to [number] for the account or fund [requestKey]. The caller has shown both to the person and been told Send. */
    fun send(context: Context, container: AppContainer, requestKey: String, number: String, text: String): Result {
        if (DemoMode.active || !canSend(context)) return Result.NO_PERMISSION
        val repository = container.balanceCheck
        val now = System.currentTimeMillis()
        if (BalanceRequests.waitLeft(repository.requests.value[requestKey], now) > 0L) return Result.TOO_SOON
        repository.record(BalanceRequest(requestKey, now, RequestStatus.SENDING))
        val report = PendingIntent.getBroadcast(
            context, (requestKey + now).hashCode(),
            Intent(context, CodeShareStatusReceiver::class.java).setAction(ACTION_STATUS).putExtra(EXTRA_KEY, requestKey).putExtra(EXTRA_AT, now),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val queued = CodeSharer.sendPlain(context, number, text, report)
        if (!queued) repository.updateStatus(requestKey, now, RequestStatus.FAILED)
        return if (queued) Result.QUEUED else Result.FAILED
    }

    /** The phone's report: the text left the phone, or it did not. Called by [CodeShareStatusReceiver]. */
    fun onReport(context: Context, intent: Intent, ok: Boolean) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        if (at == 0L) return
        val container = (context.applicationContext as ZykeMessagesApplication).container
        container.balanceCheck.updateStatus(key, at, if (ok) RequestStatus.SENT else RequestStatus.FAILED)
    }
}
