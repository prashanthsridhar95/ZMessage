package com.zyke.message.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.balance.BalanceEnquiry
import com.zyke.message.domain.balance.BalanceRefresh
import com.zyke.message.domain.balance.EnquiryPlan
import com.zyke.message.domain.balance.RefreshCandidate
import com.zyke.message.domain.balance.RefreshEntry
import com.zyke.message.domain.balance.RefreshFacts
import com.zyke.message.domain.balance.RefreshRun
import com.zyke.message.domain.balance.RefreshSkip
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * "Refresh balances before my digest" (off by default): a few minutes before the morning digest, texts each bank account's own
 * balance-enquiry number the words the person already confirmed under Check balance, through [BalanceRequester] (and so
 * [CodeSharer.sendPlain]), then lets the digest use whatever the banks have answered by then. The rules (who is asked, spacing,
 * window, outcomes) are [BalanceRefresh], tested in `:domain`.
 *
 * Only [BalanceRefreshReceiver], set by the digest's own scheduler, starts this, and only while the digest and this switch are both
 * on. It texts only banks the person confirmed once ("Always send without asking"), only the number and words shown there, never
 * in Demo, and nothing here logs or saves a number, the words or a reply: the saved run holds the account key, a label with the
 * last four digits (already in the key), the time and a reason.
 */
internal object BalanceRefresher {
    private const val SEND_GAP_MS = 1_000L
    private const val POLL_MS = 10_000L

    private fun active(container: AppContainer): Boolean {
        val settings = container.settings.settings.value
        return !DemoMode.active && settings.morningDigest && settings.refreshBalancesBeforeDigest
    }

    private fun hasSms(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    private fun airplane(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 }.getOrDefault(false)

    /** The bank accounts as the Accounts tab lists them (cards and provident funds are never asked). */
    private suspend fun banks(context: Context, container: AppContainer, now: Long): List<AccountSummary> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val messages = container.observeMessages().first()
        return Accounts.build(
            messages, container.cards.cards.value, Instant.ofEpochMilli(now), ZoneId.systemDefault(),
            hidden = container.hiddenAccounts.hidden.value, providentFunds = container.providentFundAccounts.keys.value,
            overrides = container.accountOverrides.overrides.value
        ).accounts.filter { !it.isCard && !it.isProvidentFund }.distinctBy { it.key }
    }

    /** Asks every account the rules allow, for the digest at [digestAt], and keeps what happened for the log and the digest's line. */
    suspend fun run(context: Context, container: AppContainer, digestAt: Long) {
        if (!active(container) || digestAt <= 0L) return
        val now = System.currentTimeMillis()
        val repository = container.balanceCheck
        val facts = RefreshFacts(
            canSend = BalanceRequester.canSend(context), hasSms = hasSms(context), airplane = airplane(context),
            readingPaused = container.settings.settings.value.readingPaused
        )
        val accounts = banks(context, container, now)
        val confirmed = repository.skipConfirm.value
        val setup = repository.setup.value
        val ready = accounts.associate { it.key to (BalanceEnquiry.planForAccount(it.bankName, it.bankLabel, it.last4, setup) as? EnquiryPlan.Ready) }
        val candidates = accounts.map { a ->
            val plan = ready[a.key]
            RefreshCandidate(
                a.key, "${a.bankLabel.ifBlank { a.bankName.displayString }} •••• ${a.last4}",
                plan?.let { it.number + "|" + it.text }, BalanceEnquiry.bankKey(a.bankName, a.bankLabel) in confirmed
            )
        }
        val planned = BalanceRefresh.plan(candidates, repository.requests.value, now, digestAt, ZoneId.systemDefault(), BalanceRefresh.blockedForAll(facts))
        val done = mutableListOf<RefreshEntry>()
        for (entry in planned) {
            val plan = if (entry.askedAt != null) ready[entry.key] else null
            if (plan == null) { done += entry; continue }
            done += when (BalanceRequester.send(context, container, entry.key, plan.number, plan.text)) {
                BalanceRequester.Result.QUEUED -> entry
                BalanceRequester.Result.FAILED -> entry.copy(failed = true)
                BalanceRequester.Result.NO_PERMISSION -> entry.copy(askedAt = null, skip = RefreshSkip.NO_PERMISSION)
                BalanceRequester.Result.TOO_SOON -> entry.copy(askedAt = null, skip = RefreshSkip.TOO_SOON)
            }
            delay(SEND_GAP_MS)
        }
        repository.saveRefreshRun(RefreshRun(digestAt, now, done))
    }

    /** The newest balance reading per account key (epoch millis), for the outcomes. */
    suspend fun balanceTimes(context: Context, container: AppContainer): Map<String, Long> =
        banks(context, container, System.currentTimeMillis()).associate { it.key to it.balanceAt }

    /**
     * Called by the digest just before it posts: when texts went out for it and replies are still due, waits for them, but never
     * past the end of the reply window and never more than [BalanceRefresh.DIGEST_WAIT_CAP_MS]; the digest then goes out as scheduled.
     */
    suspend fun awaitReplies(context: Context, container: AppContainer) {
        if (!active(container)) return
        val started = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val run = container.balanceCheck.refreshRun.value
            if (!BalanceRefresh.isCurrent(run, now) || now - started >= BalanceRefresh.DIGEST_WAIT_CAP_MS) return
            val wait = BalanceRefresh.digestWaitMs(run, balanceTimes(context, container), now)
            if (wait <= 0L) return
            delay(minOf(wait, POLL_MS))
        }
    }

    /** "Balances refreshed for 2 of 3 accounts" for the digest, or null when no refresh was made for it. */
    suspend fun summaryLine(context: Context, container: AppContainer): String? {
        if (!active(container)) return null
        val now = System.currentTimeMillis()
        val run = container.balanceCheck.refreshRun.value
        if (run == null || !BalanceRefresh.isCurrent(run, now)) return null
        val times = balanceTimes(context, container)
        return BalanceRefresh.summary(run.entries.map { BalanceRefresh.outcome(it, times[it.key] ?: 0L, now) })
    }
}
