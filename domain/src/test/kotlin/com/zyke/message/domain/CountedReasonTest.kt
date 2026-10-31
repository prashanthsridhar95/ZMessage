package com.zyke.message.domain

import com.zyke.message.domain.ledger.CountedReason
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountedReasonTest {
    private fun msg(content: MessageContent, setAside: SetAside? = null) = ClassifiedMessage(
        message = SmsMessage(1, "BANK", "x", 0, true),
        category = MessageCategory.OTHERS,
        merchant = Merchant("M", MerchantLogo.PERSON, MessageCategory.OTHERS),
        content = content,
        setAside = setAside
    )

    private fun tx(received: Boolean = false, cardPayment: Boolean = false, payee: String = "Cafe") =
        MessageContent.Transaction(BankName.OTHERS, payee, "100", "1234", received, false, cardPayment)

    @Test fun debitIsSpending() =
        assertEquals("Counted as spending: a debit to Cafe", CountedReason.explain(msg(tx())))

    @Test fun creditIsIncome() =
        assertTrue(CountedReason.explain(msg(tx(received = true, payee = ""))).startsWith("Counted as income"))

    @Test fun cardPaymentNotCounted() =
        assertTrue(CountedReason.explain(msg(tx(cardPayment = true))).contains("card bill payment"))

    @Test fun setAsideWinsOverCardPayment() {
        assertTrue(CountedReason.explain(msg(tx(cardPayment = true), SetAside.EXCLUDED)).contains("left it out"))
        assertTrue(CountedReason.explain(msg(tx(), SetAside.TRANSFER)).contains("transfer"))
    }

    @Test fun refundCredit() =
        assertTrue(CountedReason.explain(msg(tx(received = true)), isRefundCredit = true).contains("refund"))

    @Test fun failedNotice() =
        assertTrue(CountedReason.explain(msg(MessageContent.Notice(NoticeKind.AUTOPAY_FAILED, "5"))).contains("failed"))
}
