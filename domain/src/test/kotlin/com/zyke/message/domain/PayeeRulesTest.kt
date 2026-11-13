package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.PayeeKey
import com.zyke.message.domain.categories.RuleSource
import com.zyke.message.domain.categories.TransactionRules
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.SpendSummaryBuilder
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
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules for a payee (the person or shop money went to), above the ones for the sender, below the ones for one payment. */
class PayeeRulesTest {
    private val classifier = MessageClassifier()
    private val now = Instant.now()

    private fun debit(id: Long, payee: String, amount: String = "659.00") = classifier.classify(
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.$amount From HDFC Bank A/C *5765 To $payee On 12/11/26 Ref 6123456$id\nNot You? Call 18002586161", now.toEpochMilli() - id * 1000, true)
    )

    /** The same payment with the payee written another way (the parser stops some names at a full stop). */
    private fun withPayee(m: ClassifiedMessage, payee: String) = m.copy(content = (m.content as MessageContent.Transaction).copy(payee = payee))

    private fun credit(id: Long, payee: String) = classifier.classify(
        SmsMessage(id, "AD-HDFCBK", "Credit Alert! Rs.500.00 credited to HDFC Bank A/c XX5765 on 12-11-26 from VPA $payee (UPI 7123456$id)", now.toEpochMilli() - id * 1000, true)
    )

    private class Categories : CategoryOverrideRepository {
        override val overrides = MutableStateFlow<Map<String, MessageCategory>>(emptyMap())
        override fun set(key: String, category: MessageCategory?) { overrides.value = if (category == null) overrides.value - key else overrides.value + (key to category) }
        override fun clearAll() { overrides.value = emptyMap() }
    }

    private class Names : NicknameRepository {
        override val nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun set(key: String, name: String?) { nicknames.value = if (name.isNullOrBlank()) nicknames.value - key else nicknames.value + (key to name) }
    }

    private class Pictures : SenderImageRepository {
        override val keysWithImage = MutableStateFlow<Set<String>>(emptySet())
    }

    private class Fixed(private val inbox: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(inbox)
    }

    private fun run(inbox: List<ClassifiedMessage>, categories: Categories = Categories(), names: Names = Names(), pictures: Pictures = Pictures()): List<ClassifiedMessage> =
        runBlocking {
            ObserveMessagesUseCase(Fixed(inbox.map { it.message }), classifier, categoryOverrides = categories, nicknameRepository = names, senderImages = pictures).invoke().first()
        }

    @Test
    fun theSameShopIsOneKeyHoweverItIsWritten() {
        val keys = listOf("MR DIY", "Mr. Diy", "mr diy ", "  Mr   DIY.").map { PayeeKey.of(it) }
        assertEquals(1, keys.toSet().size)
        assertEquals("mr diy", keys.first())
    }

    @Test
    fun differentPeopleAndShopsAreDifferentKeys() {
        assertNotEquals(PayeeKey.of("Rahul Sharma"), PayeeKey.of("Rahul Verma"))
        assertNotEquals(PayeeKey.of("MR DIY"), PayeeKey.of("MR DIY STORES"))
    }

    @Test
    fun aUpiIdIsItsOwnKey_lowerCase_andNotMergedWithTheName() {
        assertEquals("40-5@okhdfcbank", PayeeKey.of("40-5@OkHdfcBank"))
        assertEquals("q123456@ybl", PayeeKey.of(" Q123456@ybl "))
        assertTrue(PayeeKey.isVpa("40-5@okhdfcbank"))
        assertNotEquals(PayeeKey.of("Rahul Sharma"), PayeeKey.of("rahul@okaxis"))
    }

    @Test
    fun companyEndingsAreWrittenOneWay() {
        val keys = listOf("Acme Private Limited", "ACME PVT LTD", "Acme Pvt. Ltd.", "acme pvt ltd").map { PayeeKey.of(it) }.toSet()
        assertEquals(setOf("acme pvt ltd"), keys)
        assertEquals("tom and jerry", PayeeKey.of("Tom & Jerry"))
    }

    @Test
    fun nothingToKeyOn_isNull() {
        assertNull(PayeeKey.of("  "))
        assertNull(PayeeKey.of("..."))
    }

    @Test
    fun aNameWithNoPunctuationKeepsTheKeyItAlwaysHad() {
        val m = debit(1, "MR DIY")
        assertEquals("mr diy", CategoryKey.of(m))
        assertNull(CategoryKey.legacyOf(m))
    }

    @Test
    fun aPayeeRuleAppliesToEveryPaymentToThatPayee_notToTheBanksOthers() {
        val a = debit(1, "MR DIY"); val b = debit(2, "MR DIY", "100.00"); val c = debit(3, "SOMEONE ELSE")
        val categories = Categories().apply { set(CategoryKey.of(a)!!, MessageCategory.SHOPPING) }
        val out = run(listOf(a, b, c), categories).associateBy { it.id }
        assertEquals(MessageCategory.SHOPPING, out[1]!!.category)
        assertEquals(MessageCategory.SHOPPING, out[2]!!.category)
        assertEquals(c.category, out[3]!!.category)
        assertTrue(out[1]!!.categoryOverridden)
        assertEquals(false, out[3]!!.categoryOverridden)
    }

    @Test
    fun precedence_thisPayment_thenPayee_thenAutomatic() {
        val m = debit(1, "MR DIY")
        val key = CategoryKey.of(m)!!
        val categories = Categories()
        assertEquals(RuleSource.AUTOMATIC, TransactionRules.categorySource(run(listOf(m), categories).single(), categories.overrides.value))
        categories.set(key, MessageCategory.FOOD)
        var shown = run(listOf(m), categories).single()
        assertEquals(MessageCategory.FOOD, shown.category)
        assertEquals(RuleSource.PAYEE, TransactionRules.categorySource(shown, categories.overrides.value))
        categories.set(CategoryKey.messageKey(1), MessageCategory.TRAVEL)
        shown = run(listOf(m), categories).single()
        assertEquals(MessageCategory.TRAVEL, shown.category)
        assertEquals(RuleSource.THIS_TRANSACTION, TransactionRules.categorySource(shown, categories.overrides.value))
    }

    @Test
    fun removingTheRule_restoresTheAutomaticCategory() {
        val m = debit(1, "MR DIY")
        val auto = run(listOf(m)).single().category
        val categories = Categories().apply { set(CategoryKey.of(m)!!, MessageCategory.CHARITY) }
        assertEquals(MessageCategory.CHARITY, run(listOf(m), categories).single().category)
        categories.set(CategoryKey.of(m)!!, null)
        assertEquals(auto, run(listOf(m), categories).single().category)
    }

    @Test
    fun aRuleAppliesToCreditsAndDebitsOfThePayee() {
        val d = debit(1, "Rahul Sharma"); val c = credit(2, "Rahul Sharma")
        assertEquals(CategoryKey.of(d), CategoryKey.of(c))
        val categories = Categories().apply { set(CategoryKey.of(d)!!, MessageCategory.FAMILY) }
        val out = run(listOf(d, c), categories)
        assertTrue(out.all { it.category == MessageCategory.FAMILY })
    }

    @Test
    fun aNicknameForThePayeeReplacesTheShownName_andOneForThePaymentBeatsIt() {
        val a = debit(1, "MR DIY"); val b = debit(2, "MR DIY")
        val names = Names().apply { set(CategoryKey.of(a)!!, "Hardware shop") }
        var out = run(listOf(a, b), names = names).associateBy { it.id }
        assertEquals("Hardware shop", out[1]!!.merchant.name)
        assertTrue(out[1]!!.merchant.renamed)
        assertEquals(RuleSource.PAYEE, TransactionRules.nicknameSource(out[1]!!))
        // The payee as written stays on the content.
        assertEquals("MR DIY", (out[1]!!.content as MessageContent.Transaction).payee)
        names.set(CategoryKey.messageKey(2), "Paint")
        out = run(listOf(a, b), names = names).associateBy { it.id }
        assertEquals("Hardware shop", out[1]!!.merchant.name)
        assertEquals("Paint", out[2]!!.merchant.name)
        assertEquals(RuleSource.THIS_TRANSACTION, TransactionRules.nicknameSource(out[2]!!))
    }

    @Test
    fun aPictureForThePayeeReachesItsPayments_andNotTheOthers() {
        val a = debit(1, "MR DIY"); val c = debit(2, "SOMEONE ELSE")
        val pictures = Pictures().apply { keysWithImage.value = setOf(CategoryKey.of(a)!!) }
        val out = run(listOf(a, c), pictures = pictures).associateBy { it.id }
        assertEquals(CategoryKey.of(a), out[1]!!.merchant.imageKey)
        assertNull(out[2]!!.merchant.imageKey)
        assertEquals(RuleSource.PAYEE, TransactionRules.pictureSource(out[1]!!, pictures.keysWithImage.value))
        assertEquals(RuleSource.AUTOMATIC, TransactionRules.pictureSource(out[2]!!, pictures.keysWithImage.value))
    }

    @Test
    fun twoPayeesOfTheSameBank_stayIndependent() {
        val a = debit(1, "MR DIY"); val b = debit(2, "RAHUL SHARMA")
        val categories = Categories().apply {
            set(CategoryKey.of(a)!!, MessageCategory.SHOPPING)
            set(CategoryKey.of(b)!!, MessageCategory.FAMILY)
        }
        val out = run(listOf(a, b), categories).associateBy { it.id }
        assertEquals(MessageCategory.SHOPPING, out[1]!!.category)
        assertEquals(MessageCategory.FAMILY, out[2]!!.category)
    }

    @Test
    fun aChoiceSavedBeforeThePunctuationWasFolded_stillApplies() {
        val m = withPayee(debit(1, "MR DIY"), "Mr. Diy")
        assertEquals("mr diy", CategoryKey.of(m))
        assertEquals("mr. diy", CategoryKey.legacyOf(m))
        val categories = Categories().apply { set("mr. diy", MessageCategory.SHOPPING) }
        val shown = CategoryKey.apply(m, categories.overrides.value)
        assertEquals(MessageCategory.SHOPPING, shown.category)
        assertEquals(RuleSource.PAYEE, TransactionRules.categorySource(shown, categories.overrides.value))
        // And the new key wins over the old one when both exist.
        categories.set("mr diy", MessageCategory.FOOD)
        assertEquals(MessageCategory.FOOD, CategoryKey.apply(m, categories.overrides.value).category)
    }

    @Test
    fun spendTotals_moveToTheNewCategory() {
        val a = debit(1, "MR DIY", "600.00"); val b = debit(2, "MR DIY", "100.00"); val c = debit(3, "SOMEONE ELSE", "50.00")
        val categories = Categories().apply { set(CategoryKey.of(a)!!, MessageCategory.SHOPPING) }
        val summary = SpendSummaryBuilder.build(run(listOf(a, b, c), categories), now, ZoneId.systemDefault())!!
        val shopping = summary.byCategory.firstOrNull { it.category == MessageCategory.SHOPPING }
        assertEquals(java.math.BigDecimal("700.00"), shopping?.amount?.setScale(2))
    }

    @Test
    fun othersAffected_countsThePayeesOtherPayments() {
        val a = debit(1, "MR DIY"); val b = withPayee(debit(2, "MR DIY"), "Mr. Diy"); val c = debit(3, "SOMEONE ELSE")
        assertEquals(listOf(2L), TransactionRules.othersWithSamePayee(a, listOf(a, b, c)).map { it.id })
        assertEquals("Also changes 1 other payment", TransactionRules.alsoChanges(1))
        assertEquals("Also changes 7 other payments", TransactionRules.alsoChanges(7))
        assertNull(TransactionRules.alsoChanges(0))
        assertEquals("Mr Diy rule", TransactionRules.sourceText(RuleSource.PAYEE, "Mr Diy"))
        assertEquals("Only this transaction", TransactionRules.sourceText(RuleSource.THIS_TRANSACTION, "Mr Diy"))
        assertEquals("MR DIY", TransactionRules.payeeName(a))
    }
}
