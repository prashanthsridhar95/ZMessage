package com.zyke.message.domain

import com.zyke.message.domain.classification.BankMessageParser
import com.zyke.message.domain.classification.FastTagMessageParser
import com.zyke.message.domain.classification.MerchantResolver
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.CourierStatus
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageClassifierTest {
    private val classifier = MessageClassifier()

    private fun sms(body: String, sender: String = "AD-TEST") =
        SmsMessage(id = 1, sender = sender, body = body, date = 0L, isRead = true)

    @Test
    fun otp_isExtractedAndCategorised() {
        val result = classifier.classify(sms("123456 is your OTP for login to Swiggy. Do not share."))
        val content = result.content as MessageContent.Otp
        assertEquals("123456", content.code)
        assertEquals(MessageCategory.OTP, result.category)
        assertEquals(MerchantLogo.SWIGGY, result.merchant.logo)
    }

    @Test
    fun otp_withCardPayment() {
        val result = classifier.classify(
            sms("Your OTP is 654321 for INR 1,250.00 on your card ending 4321 at Amazon", "HDFCBK")
        )
        val content = result.content as MessageContent.Otp
        assertEquals("654321", content.code)
        assertEquals("1250", content.paymentAmount)
        assertEquals("4321", content.cardNumber)
    }

    @Test
    fun hdfcUpiDebit_isTransaction() {
        val result = classifier.classify(
            sms("Sent Rs.500.00\nFrom HDFC Bank A/C x1234\nTo friend@ybl\nOn 12/01/25\nRef 123456")
        )
        val content = result.content as MessageContent.Transaction
        assertEquals("500", content.amount)
        assertEquals("1234", content.accountNumber)
        assertEquals("friend@ybl", content.payee)
        assertEquals(false, content.isReceived)
        assertEquals(MessageCategory.UPI_PAYMENT, result.category)
        assertEquals(MerchantLogo.UPI, result.merchant.logo)
    }

    @Test
    fun hdfcCreditAlert_isReceived() {
        val result = classifier.classify(
            sms("Credit Alert!\nRs.2500.50 credited to HDFC Bank A/c xx9876 on 01-02-25 from VPA friend@okaxis (UPI 123456789)")
        )
        val content = result.content as MessageContent.Transaction
        assertTrue(content.isReceived)
        assertEquals("2500.50", content.amount)
        assertEquals("9876", content.accountNumber)
    }

    @Test
    fun hdfcCardStatement_isCardBill() {
        val result = classifier.classify(
            sms("HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:05-03-2025 pay https://hdfcbk.io/x")
        )
        val content = result.content as MessageContent.CardBill
        assertEquals(BankName.HDFC, content.bankName)
        assertEquals("12345", content.amount)
        assertEquals("1234", content.accountNumber)
    }

    @Test
    fun unknownFormatWithDebitWord_isStillATransaction() {
        val result = classifier.classify(sms("You have paid Rs 200 to somebody"))
        val content = result.content as MessageContent.Transaction
        assertEquals("200", content.amount)
        assertEquals("somebody", content.payee)
    }

    @Test
    fun partlyGarbledHdfcCredit_isRescuedByTheGenericParser() {
        val result = classifier.classify(sms("Credit Alert! Rs.10 credited to HDFC Bank A/c garbled"))
        val content = result.content as MessageContent.Transaction
        assertEquals("10", content.amount)
        assertEquals(true, content.isReceived)
    }

    @Test
    fun moneyMentionWithoutMovement_staysGeneric() {
        assertEquals(MessageContent.Generic, classifier.classify(sms("Your order total is Rs 200")).content)
        assertEquals(MessageContent.Generic, classifier.classify(sms("Your A/c XX1234 balance is Rs 5,000.00")).content)
    }

    @Test
    fun airtelUsageAlert() {
        val result = classifier.classify(
            sms("80% Alert: You have consumed 80% of your daily high Speed Data limit. Track usage on Airtel Thanks App i.airtel.in/Get-Data")
        )
        val content = result.content as MessageContent.DataUsage
        assertEquals(80, content.percentUsed)
        assertEquals(MessageCategory.NETWORK_CARRIERS, result.category)
    }

    @Test
    fun blueDartStatus() {
        val arriving = classifier.classify(sms("Blue Dart shipment ARRIVING TODAY"))
        assertEquals(CourierStatus.ARRIVING_TODAY, (arriving.content as MessageContent.Courier).status)
        val delivered = classifier.classify(sms("Blue Dart shipment Delivered"))
        assertEquals(CourierStatus.DELIVERED, (delivered.content as MessageContent.Courier).status)
        val update = classifier.classify(sms("Blue Dart shipment in transit"))
        assertEquals(CourierStatus.UPDATE, (update.content as MessageContent.Courier).status)
    }

    @Test
    fun petpoojaLoyalty() {
        val body = "Petpooja: Loyalty wallet for BURGERMAN is loaded with 50.00 points. Current wallet balance: 120.00"
        val result = classifier.classify(sms(body, sender = "VM-PPOOJA"))
        val content = result.content as MessageContent.Loyalty
        assertTrue(content.isLoaded)
        assertEquals("120.00", content.balance)
        assertEquals(MessageCategory.LOYALTY_PROGRAMMES, result.category)
        assertEquals(MerchantLogo.BURGERMAN, result.merchant.logo)
    }

    @Test
    fun petpoojaFromOtherSender_isGeneric() {
        val result = classifier.classify(sms("Petpooja says hi", sender = "SOMEONE"))
        assertEquals(MessageContent.Generic, result.content)
    }

    @Test
    fun fastTagTollPaid() {
        val result = classifier.classify(
            sms("INR 65 toll paid from HDFC Bank Tag 1234 for vehicle no. KA01AB1234 at Hosur Toll on 12-Jan-25 10:30. Avbl. Bal.: INR435")
        )
        val event = (result.content as MessageContent.FastTag).event as FastTagEvent.TollPaid
        assertEquals("KA01AB1234", event.vehicleNumber)
        assertEquals("65", event.tollAmount)
        assertEquals("435", event.balance)
        assertEquals(MessageCategory.FASTAG, result.category)
    }

    @Test
    fun plainMessage_isGenericAndKeepsMerchantCategory() {
        val result = classifier.classify(sms("Your Swiggy order is on the way", "SWIGGY"))
        assertEquals(MessageContent.Generic, result.content)
        assertEquals(MessageCategory.FOOD, result.category)
    }

    @Test
    fun strangerMessage_isOthers() {
        val result = classifier.classify(sms("hello there", "+919999999999"))
        assertEquals(MessageCategory.OTHERS, result.category)
    }
}

class BankMessageParserTest {
    private val parser = BankMessageParser()

    @Test
    fun hdfcCardSpendVariants() {
        val spent = parser.parse("Rs.350.00 spent on HDFC Bank Card x1111 at Some Cafe on 2025-01-12:10:30:00")
        assertNotNull(spent)
        assertEquals("Some Cafe", spent!!.payee)
        assertEquals("1111", spent.accountNumber)

        val upi = parser.parse("Txn Rs.99 On HDFC Bank Card 2222 At cafe@ybl by UPI 1234567 On 12-01")
        assertEquals("cafe@ybl", upi!!.payee)
        assertEquals(BankTransactionType.SENT, upi.type)

        val due = parser.parse("Amt Due Rs.4500 on HDFC Bank Card X3333")
        assertEquals(BankTransactionType.CARD_BILL, due!!.type)
    }

    @Test
    fun axisBill() {
        val tx = parser.parse("Axis Bank Credit Card XX5678 of INR 1500.00 has been generated")
        assertEquals(BankName.AXIS, tx!!.bankName)
        assertEquals("1500.00", tx.amount)
    }

    @Test
    fun lazypayAndSimpl() {
        val lazy = parser.parse("Lazypay: Rs. 450.00 for txn AB12 on Swiggy was successful. Pay by 5th Feb, 2025")
        assertEquals("Swiggy", lazy!!.payee)
        val simpl = parser.parse("Your Simpl bill of Rs.800.00 is due")
        assertEquals(BankTransactionType.CARD_BILL, simpl!!.type)
    }

    @Test
    fun malformedKnownBank_returnsNull() {
        assertNull(parser.parse("Credit Alert! garbage"))
    }
}

class FastTagMessageParserTest {
    private val parser = FastTagMessageParser()

    @Test
    fun lowBalanceVariants() {
        assertEquals(
            FastTagEvent.RechargeNeeded("KA01AB1234"),
            parser.parse("Your vehicle KA01AB1234 is low on balance")
        )
        assertEquals(
            FastTagEvent.RechargeNeeded("KA01AB1234"),
            parser.parse("Dear user, vehicle KA01AB1234 will run out of balance soon")
        )
    }

    @Test
    fun topUp() {
        val event = parser.parse("Recharge successful: KA01AB1234 FASTag XYZ Credited with Rs 500 Ref: 123 Avbl Bal:Rs 900")
        assertEquals(FastTagEvent.ToppedUp("KA01AB1234", "500", "900"), event)
    }

    @Test
    fun activation() {
        val event = parser.parse("Your FASTag for vehicle no. KA01AB1234 is now active")
        assertEquals(FastTagEvent.Activated("KA01AB1234"), event)
    }

    @Test
    fun unrelatedText_isNull() {
        assertNull(parser.parse("hello"))
    }
}

class MerchantResolverTest {
    private val resolver = MerchantResolver()

    @Test
    fun hdfcUpiVsCard() {
        assertEquals(MerchantLogo.UPI, resolver.resolve("hdfcbank@okhdfc").logo)
        assertEquals(MerchantLogo.HDFC, resolver.resolve("HDFC").logo)
    }

    @Test
    fun overrideCategory() {
        assertEquals(MessageCategory.OTP, resolver.resolve("Swiggy", MessageCategory.OTP).category)
    }
}
