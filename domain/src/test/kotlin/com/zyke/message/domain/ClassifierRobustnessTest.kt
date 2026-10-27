package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inbox is untrusted input: no message may crash the classifier or make it slow. */
class ClassifierRobustnessTest {
    private val classifier = MessageClassifier()

    private val tokens = listOf(
        "Rs.", "Rs", "INR", "INR.", "₹", "debited", "credited", "spent", "paid", "sent", "A/c", "A/C", "XX1234",
        "x5765", "*5765", "UPI", "to", "from", "at", "by", "On", "12/01/25", "Ref", "5000.00", "1,25,000.50", ",", ".",
        ";", ":", "-", "/", "(", ")", "\n", "\r\n", "\t", " ", "Credit Alert!", "OTP", "FASTag", "Statement",
        "Total due amt", "Min due amt", "Due by:", "https://x.y/z", "Mandate Set", "will be debited", "@ybl",
        "swiggy@ybl", "*iggy@icici", "Avl Bal", "Info-", "trf to", "towards", "BURMY FOODS", "é", "日本語", "😀", "\u0000"
    )

    private fun sms(body: String, id: Long = 1) = SmsMessage(id, "AD-FUZZ", body, 0L, true)

    @Test
    fun randomMessages_neverThrow_andKeepTheOriginalMessage() {
        val random = Random(20_261_026)
        val started = System.nanoTime()

        repeat(20_000) { i ->
            val body = buildString {
                repeat(random.nextInt(1, 40)) {
                    append(tokens[random.nextInt(tokens.size)])
                    if (random.nextBoolean()) append(' ')
                }
            }
            val original = sms(body, i.toLong())
            val result = classifier.classify(original)
            assertEquals(original, result.message)
        }

        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("20k messages took $seconds s", seconds < 20)
    }

    @Test
    fun hostileInputs_stayFast_andFallBackToAPlainMessage() {
        val hostile = listOf(
            "Rs " .repeat(50_000),
            "debited ".repeat(20_000),
            "(".repeat(100_000),
            "Rs.1 " + "a".repeat(200_000) + " debited",
            "to ".repeat(30_000) + "debited Rs 5",
            "1".repeat(300_000),
            "\n".repeat(100_000),
            "INR." + "9,".repeat(50_000),
            "A/c " + "x".repeat(100_000) + " 1234 credited from " + "B".repeat(100_000)
        )

        hostile.forEach { body ->
            val started = System.nanoTime()
            val result = classifier.classify(sms(body))
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $millis ms for input of ${body.length} chars", millis < 2_000)
            assertEquals(body, result.message.body)
        }
    }

    @Test
    fun veryLongBody_isStillParsedFromItsStart() {
        val body = "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1\n" + "padding ".repeat(5_000)
        val content = classifier.classify(sms(body)).content
        assertTrue(content is MessageContent.Transaction)
    }

    @Test
    fun emptyAndBlankBodies_areHandled() {
        listOf("", " ", "\n\n").forEach { assertEquals(MessageContent.Generic, classifier.classify(sms(it)).content) }
    }
}
