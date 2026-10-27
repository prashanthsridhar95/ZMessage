package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.merge.Merges
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class MergesTest {
    private val classifier = MessageClassifier()

    private fun spend(id: Long, sender: String, amount: String, at: Long) = classifier.classify(
        SmsMessage(id, sender, "Sent Rs.$amount\nFrom HDFC Bank A/C *5765\nTo CAFE ONE\nOn 20/10/26\nRef ${id * 7}", at, true)
    )

    @Test
    fun aDuplicateLeavesTheListAndIsRememberedOnItsPrimary() {
        val messages = listOf(spend(1, "AD-HDFCBK", "500.00", 3000), spend(2, "AD-GPAY", "500.00", 2000), spend(3, "AD-HDFCBK", "90.00", 1000))
        val result = Merges.apply(messages, mapOf(2L to 1L))

        assertEquals(listOf(1L, 3L), result.map { it.id })
        assertEquals(listOf(2L), result.first().mergedMessages.map { it.id })
    }

    @Test
    fun mergingIntoSomethingAlreadyMergedGoesToTheRoot_andWhatFollowedTheDuplicateComesAlong() {
        // 2 is a duplicate of 1; now 3 is dropped on 2, so it goes to 1.
        val first = Merges.add(emptyMap(), 1, 2)
        val second = Merges.add(first, 2, 3)
        assertEquals(mapOf(2L to 1L, 3L to 1L), second)

        // 1 (with 2 and 3) is dropped on 9: all three join 9.
        val third = Merges.add(second, 9, 1)
        assertEquals(mapOf(1L to 9L, 2L to 9L, 3L to 9L), third)
        assertEquals(second, Merges.add(second, 1, 1))
    }

    @Test
    fun aDuplicateWhosePrimaryIsMissingStaysVisible_andUnmergingBringsItBack() {
        val messages = listOf(spend(2, "AD-GPAY", "500.00", 2000))
        assertEquals(listOf(2L), Merges.apply(messages, mapOf(2L to 1L)).map { it.id })
        assertEquals(emptyMap<Long, Long>(), Merges.remove(mapOf(2L to 1L), 2))
    }
}
