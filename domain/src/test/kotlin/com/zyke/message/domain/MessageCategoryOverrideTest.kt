package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCategoryOverrideTest {
    private val c = MessageClassifier()
    private fun sms(id: Long, body: String) = c.classify(SmsMessage(id, "AD-FEDBNK", body, id * 1000, true))

    @Test
    fun aChoiceForOneMessage_doesNotTouchTheOthersFromTheSameBank() {
        val salary = sms(1, "Rs 50000.00 credited to your Federal Bank A/c XX6438 on 30-09-26. Ref 1")
        val spend = sms(2, "Rs 600.00 debited from your Federal Bank A/c XX6438 on 28-10-26. Ref 2")
        val overrides = mapOf(CategoryKey.messageKey(salary.id) to MessageCategory.SALARY)

        val a = CategoryKey.apply(salary, overrides)
        val b = CategoryKey.apply(spend, overrides)
        assertEquals(MessageCategory.SALARY, a.category)
        assertTrue(a.categoryOverridden)
        assertEquals(spend.category, b.category)
    }

    @Test
    fun aMessageChoice_beatsTheSenderWideOne() {
        val spend = sms(2, "Rs 600.00 debited from your Federal Bank A/c XX6438 on 28-10-26. Ref 2")
        val key = CategoryKey.of(spend)!!
        val overrides = mapOf(key to MessageCategory.FOOD, CategoryKey.messageKey(spend.id) to MessageCategory.SHOPPING)
        assertEquals(MessageCategory.SHOPPING, CategoryKey.apply(spend, overrides).category)
    }
}
