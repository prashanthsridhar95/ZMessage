package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class InvestmentCategoriesTest {
    private val classifier = MessageClassifier()

    private fun paid(to: String) = classifier.classify(
        SmsMessage(1, "AD-HDFCBK", "Sent Rs.500.00\nFrom HDFC Bank A/C *5765\nTo $to\nOn 20/10/26\nRef 123456", 1_000_000L, true)
    ).category

    @Test
    fun investmentSavingsAndCharityPayeesGetTheirOwnCategories() {
        assertEquals(MessageCategory.INVESTMENTS, paid("ZERODHA BROKING"))
        assertEquals(MessageCategory.CHARITY, paid("AKSHAYA PATRA FOUNDATION"))
        assertEquals(MessageCategory.INSURANCE, paid("ACME LIFE INSURANCE"))
        assertEquals(MessageCategory.SAVINGS, paid("PPF ACCOUNT"))
    }
}
