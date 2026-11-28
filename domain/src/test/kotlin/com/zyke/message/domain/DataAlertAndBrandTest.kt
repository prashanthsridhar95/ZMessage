package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.classification.TelecomNotices
import com.zyke.message.domain.conversation.BrandSpelling
import com.zyke.message.domain.conversation.SenderDisplay
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.life.LifeParser
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NetworkCarrier
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telecom data alerts, subscription reminders, and naming a sender by the brand its text spells out. */
class DataAlertAndBrandTest {
    private val classifier = MessageClassifier()
    private val nov27 = LocalDate.of(2026, 11, 27).atTime(18, 42).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun classify(body: String, sender: String): ClassifiedMessage = classifier.classify(SmsMessage(1, sender, body, nov27, true))

    private fun usage(body: String, sender: String) =
        classify(body, sender).content as? MessageContent.DataUsage ?: error("not a data alert: ${classify(body, sender).content}")

    private val milk = "Hi Freshmilk Customer, You have still not subscribed for next month FreshMilk Delivery. Kindly request you to subscribe ASAP."

    // ---- Airtel and other telecom data alerts

    @Test
    fun airtelNinetyPercentAlertIsADataAlert() {
        val result = classify("90% Alert: You have consumed 90% of your data. Top-up now via Airtel app i.airtel.in/Prepaidservice", "AX-AIRTEL-S")
        val d = result.content as MessageContent.DataUsage
        assertEquals(NetworkCarrier.AIRTEL, d.carrier)
        assertEquals(90, d.percentUsed)
        assertNull(d.left)
        assertNull(d.expires)
        assertEquals(MessageCategory.NETWORK_CARRIERS, result.category)
        assertEquals("https://i.airtel.in/Prepaidservice", TelecomNotices.topUpLink(result.message.body))
    }

    @Test
    fun jioDailyDataFullyUsed() {
        val d = usage("You have used 100% of your daily data. Recharge with a data booster on myjio app.", "JD-JIOINF")
        assertEquals(NetworkCarrier.JIO, d.carrier)
        assertEquals(100, d.percentUsed)
        assertTrue(d.isDaily)
    }

    @Test
    fun viOnlyOneGbLeftHasNoPercent() {
        val d = usage("Alert! Only 1 GB data left of your daily quota. Recharge now on the Vi app.", "VM-VIINFO-S")
        assertEquals(NetworkCarrier.VI, d.carrier)
        assertFalse(d.hasPercent)
        assertEquals("1 GB", d.left)
    }

    @Test
    fun exhaustedQuotaIsFullAndKeepsTheExpiryDate() {
        val d = usage("BSNL: Your data quota is exhausted. Pack expires on 05-12-2026. Recharge to continue.", "BSNL")
        assertEquals(NetworkCarrier.BSNL, d.carrier)
        assertTrue(d.isExhausted)
        assertEquals(100, d.percentUsed)
        assertEquals(LocalDate.of(2026, 12, 5), d.expires)
    }

    @Test
    fun percentWithBalanceAndPackExpiry() {
        val d = usage("Airtel: 80% of daily data used. 0.3 GB left. Validity expires on 30-Nov-26.", "AX-AIRTEL-S")
        assertEquals(80, d.percentUsed)
        assertEquals("0.3 GB", d.left)
        assertEquals(LocalDate.of(2026, 11, 30), d.expires)
    }

    @Test
    fun jioQuotaTableReadsBalanceNotTotal() {
        val alert = "DATA Usage Alert! 90% of available data used as on 12-Aug-26 10:30 Jio Number : 9876543210 Total Data quota as per plan : 42.00 GB Balance Data quota : 4.20 GB"
        val d = usage(alert, "JD-JIOINF")
        assertEquals(90, d.percentUsed)
        assertEquals("4.20 GB", d.left)
    }

    @Test
    fun otpAndRealRechargeDebitAreNotDataAlerts() {
        val otp = classify("123456 is your OTP for Airtel recharge. Do not share. Data pack of 2 GB selected.", "AX-AIRTEL-S")
        assertTrue(otp.content is MessageContent.Otp)
        val debit = classify("Rs.299.00 debited from A/c XX1234 on 27-Nov-26 to AIRTEL PREPAID for recharge. 1.5 GB data per day. Ref 4821", "AX-HDFCBK-S")
        assertTrue(debit.content is MessageContent.Transaction)
        val paid = classify("Recharge of Rs 299 successful on Airtel. Enjoy 1.5 GB data per day. Paid via UPI.", "AX-AIRTEL-S")
        assertFalse(paid.content is MessageContent.DataUsage)
    }

    @Test
    fun anAdvertForDataIsNotAnAlert() {
        val promo = classify("Get 2 GB data free this weekend with Airtel! Recharge Rs 99 now.", "AX-AIRTEL-P")
        assertFalse(promo.content is MessageContent.DataUsage)
    }

    // ---- subscription reminders

    @Test
    fun freshMilkReminderIsAGroceriesReminderNotSpend() {
        val r = classify(milk, "AX-FRMILK-S")
        assertEquals(MessageCategory.GROCERIES, r.category)
        val life = r.content as MessageContent.Life
        assertEquals(LifeKind.RENEWAL, life.kind)
        assertEquals(LifeParser.SUBSCRIPTION_PENDING, life.status)
        assertEquals("FreshMilk", r.merchant.name)
        assertNull(life.amount)
    }

    @Test
    fun aPlainSubscribeAdvertIsNotAReminder() {
        val r = classify("Subscribe to our newsletter for the best deals on milk and more.", "AX-FRMILK-S")
        assertFalse(r.content is MessageContent.Life)
    }

    // ---- naming a sender by the brand its text spells out

    @Test
    fun abbreviatedIdTakesTheBodysSpelling() {
        assertEquals("FreshMilk", BrandSpelling.fromBody("FRMILK", milk))
        assertEquals("bigbasket", BrandSpelling.fromBody("BGBSKT", "Your bigbasket order is on the way"))
        assertEquals("BigBasket", BrandSpelling.fromBody("BGBSKT", "Your BigBasket order is on the way"))
        assertEquals("Fresh Milk", BrandSpelling.fromBody("FRMILK", "Your Fresh Milk plan is paused"))
    }

    @Test
    fun noMatchingWordMeansNoNewName() {
        assertNull(BrandSpelling.fromBody("FRMILK", "Your electricity bill is due tomorrow"))
        // The first letter must agree, and so must the last.
        assertNull(BrandSpelling.fromBody("FRMILK", "Welcome to SummerMilk delivery"))
        assertNull(BrandSpelling.fromBody("FRMILK", "Your Freshmilks plan"))
        // Too short to be an abbreviation.
        assertNull(BrandSpelling.fromBody("AMZ", "Amazon order shipped"))
    }

    @Test
    fun senderLabelUsesTheBodyNameButNeverOverridesKnownOnes() {
        val label = SenderDisplay.of("AX-FRMILK-S", body = milk)
        assertEquals("FreshMilk", label.name)
        assertTrue(label.verified)
        assertEquals("Frmilk", SenderDisplay.of("AX-FRMILK-S").name)
        assertEquals("HDFC Bank", SenderDisplay.of("AX-HDFCBK-T", body = "Dear Customer, HDFC Bank Card alert").name)
        assertEquals("My pet", SenderDisplay.of("AX-FRMILK-S", nickname = "My pet", body = milk).name)
    }

    // ---- the same brand under every way its header is written

    @Test
    fun airtelInEveryHeaderFormIsOneVerifiedBrand() {
        for (id in listOf("AIRTEL-S", "Airtel-S", "AIRTEL-T", "VM-AIRTEL-S", "Vm-Airtel-S", "JD-AIRTEL-P", "AIRTELS", "VM-AIRTELS")) {
            val label = SenderDisplay.of(id)
            assertEquals(id, "Airtel", label.name)
            assertTrue(id, label.verified)
        }
    }

    @Test
    fun aBareCapitalNameStaysUnverifiedAsBefore() {
        assertFalse(SenderDisplay.of("AIRTEL").verified)
    }

    @Test
    fun everyKnownBankKeepsItsNameInAnyForm() {
        assertEquals("HDFC Bank", SenderDisplay.of("HDFCBK-T").name)
        assertTrue(SenderDisplay.of("Hdfcbk-T").verified)
        assertEquals("ICICI Bank", SenderDisplay.of("ICICIB-S").name)
        assertEquals("ICICI Bank", SenderDisplay.of("Vm-Icicib-S").name)
        assertTrue(SenderDisplay.of("AD-HDFCBK-T").verified)
    }

    @Test
    fun unknownIdsGetNoSealAndRealBrandsKeepTheirLetter() {
        assertFalse(SenderDisplay.of("Zorbo-S").verified)
        assertEquals("Zorbo-S", SenderDisplay.of("Zorbo-S").name)
        assertFalse(SenderDisplay.of("Mo-Ann").verified)
        assertFalse(SenderDisplay.of("ZORBOS").verified)
        assertEquals("ZORBOS", SenderDisplay.of("ZORBOS").name)
        // Grofers is not a known header, and its S is part of the name.
        assertEquals("GROFERS", SenderDisplay.of("GROFERS").name)
        assertFalse(SenderDisplay.of("GROFERS").verified)
        assertEquals("Grofers", SenderDisplay.of("VM-GROFERS-S").name)
    }

    @Test
    fun airtelPackAdvertStaysAnAdvert() {
        val advert = classify("Enjoy Unlimited 4G + 5G data and Unlimited local, STD and roaming calls, 100 SMS/Day for 365 days with Airtel Fully-Unlimited pack.", "Airtel-S")
        assertFalse(advert.content is MessageContent.DataUsage)
        assertFalse(advert.content is MessageContent.Transaction)
        assertEquals(MessageCategory.NETWORK_CARRIERS, advert.category)
    }

    @Test
    fun sameBrandWrittenTwoWays() {
        assertTrue(SenderDisplay.sameBrand("HDFC", "HDFC Bank"))
        assertTrue(SenderDisplay.sameBrand("Swiggy Dineout", "Swiggy"))
        assertFalse(SenderDisplay.sameBrand("Dominos", "HDFC Bank"))
        assertFalse(SenderDisplay.sameBrand("Jo", "Jio"))
    }
}
