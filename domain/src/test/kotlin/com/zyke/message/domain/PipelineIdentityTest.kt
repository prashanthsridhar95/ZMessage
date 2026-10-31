package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.merge.Merges
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** A pipeline stage only copies the messages it changes; the rest stay the very same instances. */
class PipelineIdentityTest {
    private val sms = SmsMessage(1, "AD-HDFCBK", "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo  Burmy Foods \nOn 26/10/26\nRef 1", 2000, true)
    private val message = MessageClassifier().classify(sms)

    @Test
    fun categoryOverride_forSomethingElse_keepsTheInstance() {
        val unrelated = mapOf(CategoryKey.normalise("Someone Else") to MessageCategory.FOOD)
        assertSame(message, CategoryKey.apply(message, unrelated))
    }

    @Test
    fun categoryOverride_onAHit_copies() {
        val hit = mapOf(CategoryKey.messageKey(message.id) to MessageCategory.FOOD)
        assertNotSame(message, CategoryKey.apply(message, hit))
    }

    @Test
    fun merges_ofOtherMessages_keepTheInstance() {
        val result = Merges.apply(listOf(message), mapOf(7L to 8L))
        assertSame(message, result.single())
    }
}
