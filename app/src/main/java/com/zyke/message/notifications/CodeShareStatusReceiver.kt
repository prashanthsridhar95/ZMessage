package com.zyke.message.notifications

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.domain.sharing.ShareStatus

/**
 * The phone's report on a forwarded text: first "sent" (or why not), later, where the carrier supports it, "delivered".
 * Also carries the "sent" report of a balance request (see [BalanceRequester]). Only this app can reach it (not exported). It updates the Recent log and posts the quiet "Sent ..." or "Couldn't send ..."
 * note; it never sees the code or the number.
 */
class CodeShareStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (DemoMode.active) return
        // The same report for a "check balance by SMS" request: it only moves that request from sending to sent or failed.
        if (intent.action == BalanceRequester.ACTION_STATUS) {
            BalanceRequester.onReport(context, intent, resultCode == Activity.RESULT_OK)
            return
        }
        if (intent.action != CodeSharer.ACTION_STATUS) return
        val id = intent.getStringExtra(CodeSharer.EXTRA_LOG_ID) ?: return
        val kind = intent.getStringExtra(CodeSharer.EXTRA_KIND) ?: return
        val container = (context.applicationContext as ZykeMessagesApplication).container
        val repository = container.codeSharing
        val before = repository.log.value.firstOrNull { it.id == id } ?: return
        val ok = resultCode == Activity.RESULT_OK
        val incoming = when {
            kind == CodeSharer.KIND_SENT -> if (ok) ShareStatus.SENT else ShareStatus.FAILED
            // A delivery report that is not a plain "delivered" says nothing worth showing; the send already succeeded.
            ok -> ShareStatus.DELIVERED
            else -> return
        }
        repository.updateStatus(id, incoming)
        // The note is posted once, when the send itself is known to have worked or not.
        if (kind == CodeSharer.KIND_SENT && before.status == ShareStatus.SENDING) {
            CodeSharer.post(context, before.copy(status = incoming))
        }
    }
}
