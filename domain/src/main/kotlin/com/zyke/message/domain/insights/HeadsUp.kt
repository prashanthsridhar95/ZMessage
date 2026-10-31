package com.zyke.message.domain.insights

import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.ledger.UnusualSpend
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.ZoneId

enum class HeadsUpKind { UNUSUAL_SPEND, LOW_BALANCE }

/** What to tell the person about a payment that has just arrived. The amounts are for the unlocked phone only. */
data class HeadsUp(val kind: HeadsUpKind, val title: String, val text: String)

/** Decides whether a newly arrived payment deserves a quiet heads-up: far above the usual, or leaving the account low. */
object HeadsUps {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun evaluate(
        message: ClassifiedMessage,
        history: List<ClassifiedMessage>,
        lowBalanceFloor: BigDecimal?,
        zone: ZoneId,
        nowMs: Long = message.message.date
    ): List<HeadsUp> {
        val tx = message.content as? MessageContent.Transaction ?: return emptyList()
        if (tx.isReceived || tx.isCardPayment) return emptyList()
        val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return emptyList()
        val result = mutableListOf<HeadsUp>()

        // The person's usual: debits in the last 90 days, not counting this one (it may already be in the inbox).
        val usual = history.mapNotNull { m ->
            val t = m.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (t.isReceived || t.isCardPayment || m.isPromotional || m.isMuted || m.setAside != null || ManualEntries.isManual(m)) return@mapNotNull null
            if (m.message.body == message.message.body) return@mapNotNull null
            if (m.message.date < nowMs - UnusualSpend.HISTORY_DAYS * DAY_MS || m.message.date > nowMs) return@mapNotNull null
            t.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        }
        if (UnusualSpend.isUnusual(amount, usual)) {
            result += HeadsUp(
                HeadsUpKind.UNUSUAL_SPEND,
                "Bigger than usual",
                "${money(amount)} to ${tx.payee.ifBlank { "a payee" }} is well above your usual payments."
            )
        }

        val floor = lowBalanceFloor?.takeIf { it.signum() > 0 }
        val balance = if (floor != null && !tx.isCard) BalanceReader.statedIn(message, zone) else null
        if (floor != null && balance != null && balance.amount < floor) {
            result += HeadsUp(
                HeadsUpKind.LOW_BALANCE,
                "Balance is low",
                "${balance.bank.ifEmpty { "Account" }} •• ${balance.account} has ${money(balance.amount)}, below your ${money(floor)} floor."
            )
        }
        return result
    }

    private fun money(amount: BigDecimal) = PaceNudges.money(amount)
}
