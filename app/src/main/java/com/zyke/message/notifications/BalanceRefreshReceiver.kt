package com.zyke.message.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zyke.message.ZykeMessagesApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Woken a few minutes before the morning digest by [DigestScheduler] (not exported, no intent filter), only while the digest and
 * "Refresh balances before my digest" are both on. It does nothing in Demo mode. The work, and every rule about it, is in
 * [BalanceRefresher].
 */
class BalanceRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        val digestAt = intent.getLongExtra(EXTRA_DIGEST_AT, 0L)
        if (digestAt <= 0L) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val container = (context.applicationContext as ZykeMessagesApplication).container
                BalanceRefresher.run(context, container, digestAt)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_DIGEST_AT = "digest_at"
    }
}
