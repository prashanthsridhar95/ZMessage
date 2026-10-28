package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryOptions
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryOptionsTest {
    private val c = MessageClassifier()
    private fun sms(body: String) = c.classify(SmsMessage(1, "AD-HDFCBK", body, 0L, true))

    @Test
    fun income_offersSalary_andSpendingDoesNot() {
        val credit = sms("Rs.50000.00 credited to A/c XX1234 on 30-09-26 by NEFT ACME")
        val debit = sms("Rs.450.00 debited from A/c XX1234 on 20-10-26 to VPA cafe@okhdfc (UPI 1)")
        assertTrue(MessageCategory.SALARY in CategoryOptions.forMessage(credit))
        assertFalse(MessageCategory.FOOD in CategoryOptions.forMessage(credit))
        assertFalse(MessageCategory.SALARY in CategoryOptions.forMessage(debit))
        assertTrue(MessageCategory.FOOD in CategoryOptions.forMessage(debit))
    }

    @Test
    fun aCurrentCategoryOutsideTheList_isStillShown() {
        val debit = sms("Rs.450.00 debited from A/c XX1234 on 20-10-26 to VPA cafe@okhdfc (UPI 1)").copy(category = MessageCategory.SALARY)
        assertTrue(MessageCategory.SALARY in CategoryOptions.forMessage(debit))
    }
}
