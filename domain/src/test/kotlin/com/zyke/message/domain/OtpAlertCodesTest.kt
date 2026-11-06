package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every one-time-code text must classify as a code, with the right digits, because the live alert copies that code. */
class OtpAlertCodesTest {
    private val classifier = MessageClassifier()

    private fun code(body: String, sender: String = "AD-TEST"): String {
        val c = classifier.classify(SmsMessage(1, sender, body, 0L, isRead = false))
        assertEquals("not an OTP: $body", MessageCategory.OTP, c.category)
        val otp = c.content as MessageContent.Otp
        return otp.code
    }

    @Test
    fun commonFormats() {
        assertEquals("12345678", code("<#> The OTP is 12345678 for Rs.15,000.00 TXN to NAME. Do not share. -KVB", "VM-KVBANK"))
        assertEquals("123456", code("Your OTP is 123456"))
        assertEquals("123456", code("G-123456 is your Google verification code."))
        assertEquals("123456", code("123456 is your OTP", "AX-SWIGGY"))
        assertEquals("482913", code("Use 482913 as your Amazon OTP. Do not share it with anyone.", "AX-AMAZON"))
        assertEquals("5821", code("5821 is the OTP for your UPI registration. Valid for 10 minutes.", "VK-PAYTMB"))
        assertEquals("246810", code("246810 is your one time password (OTP) for login to Zomato", "AD-ZOMATO"))
        assertEquals("246810", code("Your verification code is 246810", "AD-APPLE"))
    }

    @Test
    fun amountBeforeTheCodeDoesNotBecomeTheCode() {
        assertEquals("482913", code("Your OTP for txn of Rs 1500 at Amazon is 482913. Valid 5 min.", "AX-HDFCBK"))
        assertEquals("482913", code("OTP for payment of INR 25000.00 on card ending 4321 is 482913", "AX-HDFCBK"))
        assertEquals("482913", code("Dear customer, use code 482913 to pay Rs 2500 to MERCHANT. OTP valid till 2026", "AX-ICICIB"))
    }

    @Test
    fun tamilAndOtherWording() {
        assertEquals("482913", code("உங்கள் OTP 482913. யாரிடமும் பகிர வேண்டாம்.", "AD-TNEB"))
        assertEquals("482913", code("482913 என்பது உங்கள் OTP எண்.", "AD-TNEB"))
        assertEquals("482913", code("Your OTP is 482913 ", "AD-X"))
        assertEquals("4829", code("OTP: 4829", "AD-X"))
        assertEquals("482913", code("OTP-482913 for login", "AD-X"))
    }

    @Test
    fun codeAtTheVeryEndOrWithPunctuation() {
        assertEquals("482913", code("Your OTP is: 482913."))
        assertEquals("482913", code("OTP 482913"))
        assertEquals("482913", code("Verification code:482913"))
        assertTrue(true)
    }
}

class CodeAlertHealthTest {
    private val ok = com.zyke.message.domain.device.CodeAlertFacts(true, false, true, true, false)
    private fun p(f: com.zyke.message.domain.device.CodeAlertFacts) = com.zyke.message.domain.device.CodeAlertHealth.problem(f)

    @Test
    fun everythingAllowedHasNoProblem() = assertEquals(null, p(ok))

    @Test
    fun eachBlockerIsNamed() {
        assertEquals(com.zyke.message.domain.device.CodeAlertProblem.SWITCHED_OFF, p(ok.copy(switchedOn = false)))
        assertEquals(com.zyke.message.domain.device.CodeAlertProblem.READING_PAUSED, p(ok.copy(readingPaused = true)))
        assertEquals(com.zyke.message.domain.device.CodeAlertProblem.CANNOT_HEAR_TEXTS, p(ok.copy(canHearTexts = false)))
        assertEquals(com.zyke.message.domain.device.CodeAlertProblem.NOTIFICATIONS_BLOCKED, p(ok.copy(notificationsAllowed = false)))
        assertEquals(com.zyke.message.domain.device.CodeAlertProblem.CHANNEL_OFF, p(ok.copy(channelOff = true)))
    }

    @Test
    fun theSwitchComesFirstAndPermissionsBeforeTheChannel() {
        assertEquals(com.zyke.message.domain.device.CodeAlertProblem.SWITCHED_OFF, p(com.zyke.message.domain.device.CodeAlertFacts(false, true, false, false, true)))
        assertEquals(com.zyke.message.domain.device.CodeAlertProblem.NOTIFICATIONS_BLOCKED, p(ok.copy(notificationsAllowed = false, channelOff = true)))
    }
}
