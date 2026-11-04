package com.zyke.message.presentation.investments

import android.content.Intent
import androidx.compose.runtime.mutableStateOf

/** A request, from outside the app, to open the Investments tab (a deposit's reminder notification), on one holding when given. */
data class InvestmentRequest(val holdingId: String?)

/**
 * The hook by which a maturity or installment reminder opens Investments. MainActivity puts a request here from the
 * incoming intent; the Accounts page takes it, switches to its Investments tab and opens the deposit.
 */
object InvestmentRequests {
    const val ACTION_OPEN = "com.zyke.message.action.OPEN_INVESTMENTS"
    const val EXTRA_HOLDING = "com.zyke.message.extra.HOLDING"

    val pending = mutableStateOf<InvestmentRequest?>(null)

    fun fromIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN) return
        pending.value = InvestmentRequest(intent.getStringExtra(EXTRA_HOLDING))
    }
}
