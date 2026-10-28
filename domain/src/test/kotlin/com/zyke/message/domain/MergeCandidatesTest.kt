package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.merge.MergeCandidates
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeCandidatesTest {
    private val c = MessageClassifier()
    private val hour = 3_600_000L
    private fun sms(id: Long, body: String, at: Long) = c.classify(SmsMessage(id, "AD-HDFCBK", body, at, true))

    @Test
    fun sameAmountSameDirectionCloseInTime_isACandidate() {
        val a = sms(1, "Rs.450.00 debited from A/c XX1234 on 12-10-26 to VPA cafe@okhdfc (UPI 111)", 100 * hour)
        val b = sms(2, "Sent Rs.450.00 From HDFC Bank A/C *1234 To CAFE On 12/10/26 Ref 222", 101 * hour)
        val other = sms(3, "Rs.999.00 debited from A/c XX1234 on 12-10-26 to VPA shop@okhdfc (UPI 333)", 101 * hour)
        val far = sms(4, "Rs.450.00 debited from A/c XX1234 on 20-10-26 to VPA cafe@okhdfc (UPI 444)", 300 * hour)
        assertEquals(listOf(2L), MergeCandidates.find(a, listOf(a, b, other, far)).map { it.id })
    }
}
