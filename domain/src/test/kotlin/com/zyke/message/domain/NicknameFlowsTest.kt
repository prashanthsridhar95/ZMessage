package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.BrandLogo
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.nickname.NicknameStage
import com.zyke.message.domain.nickname.PendingNicknames
import com.zyke.message.domain.nickname.PictureSearchQuery
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nickname flows: what the picture search and the logo lookup start from, and that a nickname applies and clears the same everywhere. */
class NicknameFlowsTest {
    private val classifier = MessageClassifier()
    private fun debit(id: Long, payee: String, sender: String = "AD-HDFCBK", account: String = "5765") =
        classifier.classify(SmsMessage(id, sender, "Sent Rs.540.00\nFrom HDFC Bank A/C *$account\nTo  $payee \nOn 26/10/26\nRef $id", 5000 - id, true))

    private class Names(initial: Map<String, String>) : NicknameRepository {
        override val nicknames: StateFlow<Map<String, String>> = MutableStateFlow(initial)
        override fun set(key: String, name: String?) = Unit
    }
    private class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    private fun pipeline(inbox: List<SmsMessage>, names: Map<String, String>): List<ClassifiedMessage> = runBlocking {
        ObserveMessagesUseCase(Sms(inbox), MessageClassifier(), Dispatchers.Unconfined, nicknameRepository = Names(names))().first()
    }

    // ---- the picture search starts from the name the person sees

    @Test
    fun nicknameLeadsAndTheNameInTheMessageIsTheAlternative() {
        val named = NicknameStage.apply(debit(1, "MR DIY"), mapOf(CategoryKey.of(debit(1, "MR DIY"))!! to "Hardware shop"))
        val q = PictureSearchQuery.of(named)
        assertEquals("Hardware shop", q.primary)
        assertEquals("MR DIY", q.alternative)
    }

    @Test
    fun withoutANicknameTheShownNameLeadsAndThereIsNoDuplicateAlternative() {
        val q = PictureSearchQuery.of(null, "Mr Diy", "MR DIY")
        assertEquals("Mr Diy", q.primary)
        assertNull("same name apart from case is not a second suggestion", q.alternative)
        assertEquals("MR DIY", PictureSearchQuery.of(debit(1, "MR DIY")).primary.let { if (it.equals("mr diy", true)) "MR DIY" else it })
    }

    @Test
    fun aPayeeWidePictureIgnoresANameGivenToOnePayment() {
        val message = debit(1, "MR DIY")
        val own = NicknameStage.apply(message, mapOf(CategoryKey.messageKey(1) to "Just this one"))
        assertEquals("Just this one", PictureSearchQuery.of(own).primary)
        val forPayee = PictureSearchQuery.forPayee(own, payeeNickname = null)
        assertEquals("MR DIY", forPayee.primary)
        assertNull(forPayee.alternative)
        val named = PictureSearchQuery.forPayee(own, payeeNickname = "Hardware shop")
        assertEquals("Hardware shop", named.primary)
        assertEquals("MR DIY", named.alternative)
    }

    @Test
    fun theQueryNeverCarriesNumbersOrUpiHosts() {
        assertEquals("", PictureSearchQuery.clean("9876543210@ybl"))
        assertEquals("", PictureSearchQuery.clean("XX5765"))
        assertEquals("", PictureSearchQuery.clean("Rs.540.00"))
        assertEquals("Burmy Foods", PictureSearchQuery.clean("Burmy Foods A/C 5765 Rs 540"))
        assertEquals("burmy", PictureSearchQuery.clean("burmy@okhdfcbank"))
        assertEquals("mrdiy store", PictureSearchQuery.clean("mrdiy.store@okicici"))
        assertEquals("NFLX SUBS", PictureSearchQuery.clean("NFLX*SUBS"))
        assertEquals("7 Eleven", PictureSearchQuery.clean("7-Eleven"))
        assertEquals("Studio 21", PictureSearchQuery.clean("Studio 21"))
        assertEquals("", PictureSearchQuery.clean("4111111111111111"))
        assertTrue(PictureSearchQuery.clean("A".repeat(200)).length <= 60)
    }

    @Test
    fun anUnusableNameFallsThroughToTheNextOne() {
        val q = PictureSearchQuery.of(nickname = "9876543210", shown = "Ravi Kumar", original = "9876543210@ybl")
        assertEquals("Ravi Kumar", q.primary)
        assertNull(q.alternative)
        assertEquals("", PictureSearchQuery.of(null, "123456", "A/C 5765").primary)
    }

    // ---- brand logo order

    @Test
    fun theNicknameIsLookedUpFirstThenTheOriginalName() {
        assertEquals(listOf("Netflix", "NFLX SUBS"), BrandLogo.names("Netflix", "NFLX SUBS"))
        assertEquals(listOf("Swiggy"), BrandLogo.names("Swiggy", "swiggy "))
        val both = BrandLogo.resolve(BrandLogo.names("Netflix", "Swiggy Instamart")) { false }
        assertEquals("Netflix" to "netflix.com", both)
        // A nickname that is no brand: the original name still finds its logo.
        assertEquals("Swiggy Instamart" to "swiggy.com", BrandLogo.resolve(BrandLogo.names("Dinner", "Swiggy Instamart")) { false })
        assertNull(BrandLogo.resolve(BrandLogo.names("Hardware shop", "MR DIY")) { false })
    }

    @Test
    fun aLogoTurnedOffStaysOffForEveryNameItCouldBeFoundBy() {
        val names = BrandLogo.names("Netflix", "Netflix India")
        val off = BrandLogo.withLogo(names).map(BrandLogo::key).toSet()
        assertEquals(2, off.size)
        assertNull(BrandLogo.resolve(names) { BrandLogo.key(it) in off })
        // Turned off for the nickname only: the original name's logo is not shown instead (it is the same brand).
        assertEquals("Netflix India" to "netflix.com", BrandLogo.resolve(names) { BrandLogo.key(it) == "netflix" })
    }

    // ---- a nickname applies to the payee everywhere and clearing restores

    @Test
    fun theSamePayeeUnderAnotherBankGetsTheNickname() {
        val a = debit(1, "MR DIY")
        val b = debit(2, "Mr  Diy", sender = "VM-ICICIT", account = "9001")
        val c = debit(3, "SOMEONE ELSE")
        val out = pipeline(listOf(a, b, c).map { it.message }, mapOf(CategoryKey.of(a)!! to "Hardware shop"))
        assertEquals(CategoryKey.of(out[0]), CategoryKey.of(out[1]))
        assertEquals(listOf(true, true, false), out.sortedBy { it.id }.map { it.merchant.renamed })
        assertEquals("Hardware shop", out.first { it.id == 2L }.merchant.name)
        assertEquals("MR DIY", out.first { it.id == 1L }.merchant.originalName?.trim())
        assertNull(out.first { it.id == 3L }.merchant.originalName)
        // The key a category or a picture is kept under does not change with the name.
        assertEquals(CategoryKey.of(a), CategoryKey.of(out.first { it.id == 1L }))
    }

    @Test
    fun aNameForOnePaymentBeatsTheNameForThePayeeAndOthersKeepThePayeesName() {
        val a = debit(1, "MR DIY")
        val b = debit(2, "MR DIY")
        val names = mapOf(CategoryKey.of(a)!! to "Hardware shop", CategoryKey.messageKey(1) to "The drill")
        val out = pipeline(listOf(a, b).map { it.message }, names).sortedBy { it.id }
        assertEquals("The drill", out[0].merchant.name)
        assertTrue(out[0].nicknameOwn)
        assertEquals("Hardware shop", out[1].merchant.name)
        assertFalse(out[1].nicknameOwn)
        // Both remember the one original name, whichever level named them.
        assertEquals(listOf("MR DIY", "MR DIY"), out.map { it.merchant.originalName?.trim() })
    }

    @Test
    fun clearingTheNicknameRestoresTheOriginalOnTheScreensOwnMessages() {
        val a = debit(1, "MR DIY")
        val key = CategoryKey.of(a)!!
        val named = NicknameStage.reapplyAll(listOf(a), mapOf(key to "Hardware shop"))
        assertEquals("Hardware shop", named[0].merchant.name)
        val cleared = NicknameStage.reapplyAll(named, emptyMap())
        assertEquals(a.merchant, cleared[0].merchant)
        assertEquals(a, cleared[0])
        // Taking away the payment's own name falls back to the payee's.
        val both = NicknameStage.reapplyAll(listOf(a), mapOf(key to "Hardware shop", CategoryKey.messageKey(1) to "Drill"))
        val back = NicknameStage.reapplyAll(both, mapOf(key to "Hardware shop"))
        assertEquals("Hardware shop", back[0].merchant.name)
        assertFalse(back[0].nicknameOwn)
    }

    @Test
    fun reapplyingNicknamesOnTheScreenEqualsRunningThePipeline() {
        val list = listOf(debit(1, "MR DIY"), debit(2, "MR DIY", sender = "VM-ICICIT"), debit(3, "Burmy Foods"), debit(4, "MR DIY"))
        val sms = list.map { it.message }
        val key = CategoryKey.of(list[0])!!
        val steps = listOf(
            emptyMap(),
            mapOf(key to "Hardware shop"),
            mapOf(key to "Hardware shop", CategoryKey.messageKey(4) to "Drill"),
            mapOf(key to "Tools", CategoryKey.of(list[2])!! to "Lunch"),
            emptyMap()
        )
        // The screen holds the pipeline's list for the first step and only ever reapplies after that.
        var shown = pipeline(sms, steps[0])
        for (names in steps) {
            shown = NicknameStage.reapplyAll(shown, names)
            assertEquals(pipeline(sms, names).sortedBy { it.id }, shown.sortedBy { it.id })
        }
    }

    @Test
    fun reapplyingChangesNothingForMessagesThatAlreadyShowTheNicknames() {
        val a = debit(1, "MR DIY")
        val names = mapOf(CategoryKey.of(a)!! to "Hardware shop")
        val once = NicknameStage.reapplyAll(listOf(a, debit(2, "Other")), names)
        val twice = NicknameStage.reapplyAll(once, names)
        assertSame(once, twice)
        val plain = listOf(a)
        assertSame("no nicknames at all: the same list", plain, NicknameStage.reapplyAll(plain, emptyMap()))
    }

    @Test
    fun aHandMadeEntryKeepsItsOwnNameWhenANicknameIsTakenAway() {
        val entry = com.zyke.message.domain.ledger.ManualEntries.toMessage(
            com.zyke.message.domain.ledger.ManualEntry(
                1, com.zyke.message.domain.ledger.ManualKind.EXPENSE, java.math.BigDecimal("20"), "Cash tea",
                com.zyke.message.domain.model.MessageCategory.FOOD, java.time.LocalDate.of(2026, 10, 1)
            ),
            java.time.ZoneId.systemDefault()
        )
        val named = NicknameStage.apply(entry, mapOf(CategoryKey.messageKey(entry.id) to "Chai"))
        assertEquals("Chai", named.merchant.name)
        val back = NicknameStage.withoutNickname(named)
        assertEquals("Cash tea", back.merchant.name)
        assertTrue(back.merchant.renamed)
    }

    // ---- shown at once

    @Test
    fun aListThatWasOnItsWayBeforeTheTapDoesNotTakeTheNameAway() {
        var now = 0L
        val pending = PendingNicknames({ now }, 1_000L)
        val a = debit(1, "MR DIY")
        val stale = listOf(a)
        val names = mapOf(CategoryKey.of(a)!! to "Hardware shop")
        assertSame("no change yet: lists pass untouched", stale, pending.overlay(stale, names))
        pending.changed()
        assertEquals("Hardware shop", pending.overlay(stale, names)[0].merchant.name)
        now = 500
        assertEquals("Hardware shop", pending.overlay(stale, names)[0].merchant.name)
        now = 2_000
        assertSame("after the window the pipeline has caught up", stale, pending.overlay(stale, names))
    }

    @Test
    fun theRowsShowAClearedNameAtOnce() {
        val a = debit(1, "MR DIY")
        val key = CategoryKey.of(a)!!
        val named = NicknameStage.apply(a, mapOf(key to "Hardware shop"))
        val pending = PendingNicknames({ 0L }, 1_000L)
        pending.changed()
        val shown = pending.overlay(listOf(named), emptyMap())
        assertEquals("mr diy", shown[0].merchant.name.trim().lowercase())
        assertFalse(shown[0].merchant.renamed)
        assertNotNull(shown)
    }

    // ---- Settings > Your rules

    @Test
    fun aPayeeFiledUnderTheOwnCategoryOfThePersonIsListedAsARule() {
        val a = debit(1, "MR DIY")
        val key = CategoryKey.of(a)!!
        val view = com.zyke.message.domain.categories.Rules.describe(listOf(a), emptyMap(), emptyMap(), emptySet(), customAssignments = mapOf(key to "c1"))
        val rule = view.rules.single()
        assertEquals("c1", rule.customCategoryId)
        assertEquals(1, rule.affects)
        assertTrue(rule.payee)
        assertTrue(com.zyke.message.domain.categories.Rules.describe(listOf(a), emptyMap(), emptyMap(), emptySet()).isEmpty)
    }
}
