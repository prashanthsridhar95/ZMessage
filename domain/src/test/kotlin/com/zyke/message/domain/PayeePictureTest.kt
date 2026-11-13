package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.SenderImageRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A picture chosen for a payee reaches every row of that payee, whatever else the pipeline does to the row. */
class PayeePictureTest {
    private val classifier = MessageClassifier()
    private val now = Instant.now().toEpochMilli()

    private val mrDiy = "Sent Rs.659.00 From HDFC Bank A/C *5765 To MR DIY On 12/11/26 Ref 668285863460 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"

    private fun sms(id: Long, body: String, sender: String = "AD-HDFCBK") = SmsMessage(id, sender, body, now - id * 1000, true)

    private class Pictures : SenderImageRepository {
        override val keysWithImage = MutableStateFlow<Set<String>>(emptySet())
    }

    private class Names : NicknameRepository {
        override val nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun set(key: String, name: String?) { nicknames.value = if (name.isNullOrBlank()) nicknames.value - key else nicknames.value + (key to name) }
    }

    private class Categories : CategoryOverrideRepository {
        override val overrides = MutableStateFlow<Map<String, MessageCategory>>(emptyMap())
        override fun set(key: String, category: MessageCategory?) { overrides.value = if (category == null) overrides.value - key else overrides.value + (key to category) }
        override fun clearAll() { overrides.value = emptyMap() }
    }

    private class Fixed(private val inbox: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(inbox)
    }

    private fun run(
        inbox: List<SmsMessage>,
        pictures: Pictures = Pictures(),
        names: Names = Names(),
        categories: Categories = Categories()
    ): List<ClassifiedMessage> = runBlocking {
        ObserveMessagesUseCase(Fixed(inbox), classifier, categoryOverrides = categories, nicknameRepository = names, senderImages = pictures).invoke().first()
    }

    /** What the detail sheet computes at pick time: the key of the finished row. */
    private fun pickKey(out: List<ClassifiedMessage>, id: Long): String = CategoryKey.of(out.first { it.id == id })!!

    private fun assertPictured(body: String, names: Names = Names(), categories: Categories = Categories(), sender: String = "AD-HDFCBK") {
        val plain = run(listOf(sms(1, body, sender)), names = names, categories = categories)
        val key = pickKey(plain, 1)
        assertNull(plain[0].merchant.imageKey)
        val out = run(listOf(sms(1, body, sender)), Pictures().apply { keysWithImage.value = setOf(key) }, names, categories)
        assertEquals(key, out.first { it.id == 1L }.merchant.imageKey)
    }

    @Test fun theDebitToMrDiyShowsTheChosenPicture() {
        val out = run(listOf(sms(1, mrDiy)))
        assertEquals("mr diy", (out[0].content as MessageContent.Transaction).payee.let { CategoryKey.of(out[0]) })
        assertPictured(mrDiy)
    }

    @Test fun aCreditFromAVpa() = assertPictured("Credit Alert! Rs.500.00 credited to HDFC Bank A/c XX5765 on 12-11-26 from VPA 40-5@okhdfcbank (UPI 712345678901)")

    @Test fun aCardPayment() = assertPictured("Spent Rs.1200.00 On HDFC Bank Card 1234 At SWIGGY On 2026-11-12:10:11:12 Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 1234 to 7308080808")

    @Test fun withANicknameSet() {
        val names = Names().apply { set("mr diy", "Hardware") }
        assertPictured(mrDiy, names = names)
    }

    @Test fun withACategoryOverride() {
        val categories = Categories().apply { set("mr diy", MessageCategory.SHOPPING) }
        assertPictured(mrDiy, categories = categories)
    }

    @Test fun otherPaymentsToThePayeeShowItToo() {
        val other = "Sent Rs.80.00\nFrom HDFC Bank A/C *1111\nTo Mr. DIY\nOn 13/11/26\nRef 111285863460"
        val otherBank = "Rs.90.00 debited from A/c XX9999 on 13-11-26 to VPA mr diy (UPI Ref 998285863460). -SBI"
        val out = run(listOf(sms(1, mrDiy), sms(2, other), sms(3, otherBank, "AX-SBIUPI")), Pictures().apply { keysWithImage.value = setOf("mr diy") })
        assertEquals("mr diy", out.first { it.id == 1L }.merchant.imageKey)
        assertEquals("mr diy", out.first { it.id == 2L }.merchant.imageKey)
    }

    @Test fun aPictureKeptUnderTheOldSpellingStillShows() {
        val out = run(listOf(sms(1, mrDiy)), Pictures().apply { keysWithImage.value = setOf("mr diy") })
        assertNotNull(out[0].merchant.imageKey)
    }

    @Test fun aPictureForOnePaymentBeatsThePayeesAndOthersKeepThePayees() {
        val second = sms(2, mrDiy.replace("659.00", "10.00").replace("668285863460", "668285863461"))
        val out = run(listOf(sms(1, mrDiy), second), Pictures().apply { keysWithImage.value = setOf("mr diy", CategoryKey.messageKey(1)) })
        assertEquals("msg:1", out.first { it.id == 1L }.merchant.imageKey)
        assertEquals("mr diy", out.first { it.id == 2L }.merchant.imageKey)
    }

    @Test fun removingThePictureRestoresTheMonogram() {
        val pictures = Pictures().apply { keysWithImage.value = setOf("mr diy") }
        val uc = ObserveMessagesUseCase(Fixed(listOf(sms(1, mrDiy))), classifier, senderImages = pictures)
        runBlocking {
            assertEquals("mr diy", uc().first()[0].merchant.imageKey)
            pictures.keysWithImage.value = emptySet()
            assertNull(uc().first()[0].merchant.imageKey)
        }
    }
}
