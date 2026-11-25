package com.zyke.message.domain.places

import com.zyke.message.domain.categories.PayeeKey
import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent

/** Whether a live message earns one location fix, and if not, why (for the developer log: the reason only, never a name or amount). */
sealed interface CaptureDecision {
    data object Capture : CaptureDecision
    data class Skip(val reason: String) : CaptureDecision
}

/**
 * The one rule for "this text is a purchase made somewhere". It asks [Counting] whether the message is spending, so a
 * credit, a refund, a bill, a code or a card-bill payment never qualifies, and adds what is only known at the till: an
 * own-account transfer, a FASTag recharge, a card-bill payment worded as a debit, and online-only payees (see [OnlineMerchants]).
 * A message arriving live has not been through the inbox pipeline, so the rules the person set (set-asides, card-bill
 * answers) do not apply yet; those are caught again when the map is built, because only counted spend is drawn.
 */
object PlaceCapture {
    private val OWN_TRANSFER = Regex("(?i)\\b(?:self|own account|own a/c|to self|sweep|fd |fixed deposit|recurring deposit|rd )\\b")
    private val CARD_BILL = Regex("(?i)\\b(?:credit card (?:bill|payment|outstanding)|card (?:bill|payment)|cc bill|bill ?desk)\\b")
    private val RECHARGE = Regex("(?i)\\b(?:fast ?tag|netc|wallet|upi lite|recharge)\\b")
    private val SKIP_CATEGORIES = setOf(
        MessageCategory.CREDIT_CARDS, MessageCategory.FASTAG, MessageCategory.INVESTMENTS, MessageCategory.SAVINGS,
        MessageCategory.INSURANCE, MessageCategory.PAY_LATER, MessageCategory.NETWORK_CARRIERS, MessageCategory.OTP
    )

    fun decide(message: ClassifiedMessage, settings: SpendPlaceSettings): CaptureDecision {
        val tx = message.content as? MessageContent.Transaction ?: return CaptureDecision.Skip("not a payment")
        if (tx.isReceived) return CaptureDecision.Skip("money in")
        if (tx.isCardPayment) return CaptureDecision.Skip("card bill payment")
        if (Counting.of(message) !is Counted.Spend) return CaptureDecision.Skip("not counted as spending")
        if (message.category in SKIP_CATEGORIES) return CaptureDecision.Skip("category without a place")
        val body = message.message.body
        if (CARD_BILL.containsMatchIn(body) || CARD_BILL.containsMatchIn(tx.payee)) return CaptureDecision.Skip("card bill payment")
        if (RECHARGE.containsMatchIn(body) || RECHARGE.containsMatchIn(tx.payee)) return CaptureDecision.Skip("recharge or top-up")
        if (OWN_TRANSFER.containsMatchIn(tx.payee)) return CaptureDecision.Skip("own account")
        val payeeKey = PayeeKey.of(tx.payee)
        if (payeeKey != null && payeeKey in settings.skipOverrides) return CaptureDecision.Skip("you chose to skip this payee")
        if (payeeKey != null && payeeKey in settings.captureOverrides) return CaptureDecision.Capture
        if (OnlineMerchants.isOnline(tx.payee, body)) return CaptureDecision.Skip("online payee")
        return CaptureDecision.Capture
    }
}
