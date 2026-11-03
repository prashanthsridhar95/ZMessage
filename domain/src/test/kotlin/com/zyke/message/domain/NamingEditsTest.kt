package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.CustomCategories
import com.zyke.message.domain.categories.CustomCategoryState
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CustomCategory
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.nickname.CategoryChoice
import com.zyke.message.domain.nickname.NamingEdits
import com.zyke.message.domain.nickname.NamingEdits.Target
import com.zyke.message.domain.nickname.NicknameProfiles
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.CustomCategoryStore
import com.zyke.message.domain.repository.NicknameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingEditsTest {
    private class Names : NicknameRepository {
        val flow = MutableStateFlow<Map<String, String>>(emptyMap())
        override val nicknames: StateFlow<Map<String, String>> = flow
        override fun set(key: String, name: String?) { flow.value = if (name.isNullOrBlank()) flow.value - key else flow.value + (key to name) }
    }

    private class Overrides : CategoryOverrideRepository {
        val flow = MutableStateFlow<Map<String, MessageCategory>>(emptyMap())
        override val overrides: StateFlow<Map<String, MessageCategory>> = flow
        override fun set(key: String, category: MessageCategory?) { flow.value = if (category == null) flow.value - key else flow.value + (key to category) }
        override fun clearAll() { flow.value = emptyMap() }
    }

    private val names = Names()
    private val overrides = Overrides()
    private val sip = CustomCategory("c1", "Long-term SIPs")
    private val customs = CustomCategoryStore(CustomCategoryState(listOf(sip), emptyMap()))
    private val edits = NamingEdits(names, overrides, customs)
    private var clock = 1_000L

    private fun tx(id: Long, payee: String, category: MessageCategory = MessageCategory.OTHERS) = ClassifiedMessage(
        message = SmsMessage(id, "AD-HDFCBK", "Rs 100 debited to $payee", clock++, true),
        category = category,
        merchant = Merchant(payee, MerchantLogo.UPI, category),
        content = MessageContent.Transaction(BankName.HDFC, payee, "100.00", "5765", false, false)
    )

    /** The inbox as the app sees it: the person's categories (built-in, then their own) applied. */
    private fun inbox(vararg raw: ClassifiedMessage): List<ClassifiedMessage> =
        CustomCategories.apply(raw.map { CategoryKey.apply(it, overrides.flow.value) }, customs.state.value)

    private fun key(payee: String) = CategoryKey.normalise(payee)
    private fun rename(targets: List<Target>, name: String?, category: CategoryChoice? = null, messages: List<ClassifiedMessage>) =
        edits.apply(NamingEdits.Request(targets, name, category), messages, emptySet())

    @Test fun nameComparisonIgnoresCaseSpacePunctuationEmojiAndAccents() {
        val one = NicknameProfiles.normalise("Bajaj SIP")
        assertEquals(one, NicknameProfiles.normalise("  bajaj   sip "))
        assertEquals(one, NicknameProfiles.normalise("Bajaj-SIP!"))
        assertEquals(one, NicknameProfiles.normalise("💸 Bajaj SIP"))
        assertEquals(NicknameProfiles.normalise("Café"), NicknameProfiles.normalise("cafe"))
        assertEquals(NicknameProfiles.normalise("Domino's"), NicknameProfiles.normalise("Dominos"))
        assertFalse(one == NicknameProfiles.normalise("Bajaj"))
    }

    @Test fun aTypedNameBringsTheCategoryOfPaymentsAlreadyCarryingIt() {
        names.set(key("Bajaj Finserv"), "Bajaj SIP")
        overrides.set(key("Bajaj Finserv"), MessageCategory.INVESTMENTS)
        val messages = inbox(tx(1, "Bajaj Finserv"), tx(2, "HDFC MF Mandate"))

        // Typed with other case, spacing and punctuation than the stored name.
        val out = rename(listOf(Target(key("HDFC MF Mandate"), setOf(2))), "  bajaj-SIP ", messages = messages)
        assertEquals(CategoryChoice.of(MessageCategory.INVESTMENTS), out.inherited)
        assertEquals(MessageCategory.INVESTMENTS, overrides.flow.value[key("HDFC MF Mandate")])
        assertEquals("bajaj-SIP", names.flow.value[key("HDFC MF Mandate")])
    }

    @Test fun aPickedSuggestionAndATypedNameDoTheSame() {
        names.set(key("A"), "Bajaj SIP")
        overrides.set(key("A"), MessageCategory.INVESTMENTS)
        val messages = inbox(tx(1, "A"), tx(2, "B"), tx(3, "C"))
        rename(listOf(Target(key("B"), setOf(2))), "Bajaj SIP", messages = messages)
        rename(listOf(Target(key("C"), setOf(3))), "bajaj sip", messages = messages)
        assertEquals(overrides.flow.value[key("B")], overrides.flow.value[key("C")])
        assertEquals(MessageCategory.INVESTMENTS, overrides.flow.value[key("B")])
    }

    @Test fun renamingOnePaymentTakesThePayeeLevelCategoryOfTheName() {
        names.set(key("Zerodha"), "Stocks")
        overrides.set(key("Zerodha"), MessageCategory.INVESTMENTS)
        val messages = inbox(tx(1, "Zerodha"), tx(2, "Some UPI"))
        val own = CategoryKey.messageKey(2)
        rename(listOf(Target(own, setOf(2))), "Stocks", messages = messages)
        assertEquals(MessageCategory.INVESTMENTS, overrides.flow.value[own])
        assertNull(overrides.flow.value[key("Some UPI")])
    }

    @Test fun aPaymentRenamedOnItsOwnCountsAsACarrier() {
        val own1 = CategoryKey.messageKey(1)
        names.set(own1, "Rent")
        overrides.set(own1, MessageCategory.UTILITIES)
        val messages = inbox(tx(1, "Landlord"), tx(2, "Other"))
        rename(listOf(Target(key("Other"), setOf(2))), "Rent", messages = messages)
        assertEquals(MessageCategory.UTILITIES, overrides.flow.value[key("Other")])
    }

    @Test fun aCustomCategoryOfTheNameIsTaken() {
        names.set(key("Bajaj"), "SIPs")
        customs.assign(key("Bajaj"), sip.id)
        val messages = inbox(tx(1, "Bajaj"), tx(2, "Other"))
        val out = rename(listOf(Target(key("Other"), setOf(2))), "SIPs", messages = messages)
        assertEquals(CategoryChoice.custom(sip.id), out.inherited)
        assertEquals(sip.id, customs.state.value.assignments[key("Other")])
        assertNull(overrides.flow.value[key("Other")])
    }

    @Test fun majorityWinsAndTheNewestBreaksATie() {
        names.set(key("A"), "Fund"); names.set(key("B"), "Fund"); names.set(key("C"), "Fund")
        overrides.set(key("A"), MessageCategory.INVESTMENTS)
        overrides.set(key("B"), MessageCategory.INVESTMENTS)
        overrides.set(key("C"), MessageCategory.SAVINGS)
        val majority = inbox(tx(1, "A"), tx(2, "B"), tx(3, "C"))
        assertEquals(CategoryChoice.of(MessageCategory.INVESTMENTS), NicknameProfiles.profile("Fund", names.flow.value, majority, emptySet())!!.category)

        overrides.set(key("B"), MessageCategory.SAVINGS)
        overrides.set(key("A"), MessageCategory.INVESTMENTS)
        // One against one against one is not a majority of anything: two savings, one investments.
        val savingsMajority = inbox(tx(1, "A"), tx(2, "B"), tx(3, "C"))
        assertEquals(CategoryChoice.of(MessageCategory.SAVINGS), NicknameProfiles.profile("Fund", names.flow.value, savingsMajority, emptySet())!!.category)

        names.set(key("C"), null)
        // A tie of one each: the newer payment (B, savings; id 2 is newer than id 1) decides.
        val tie = inbox(tx(1, "A"), tx(2, "B"))
        assertEquals(CategoryChoice.of(MessageCategory.SAVINGS), NicknameProfiles.profile("Fund", names.flow.value, tie, emptySet())!!.category)
    }

    @Test fun automaticCategoriesCountOnlyWhenNothingWasChosenAndVagueOnesNever() {
        names.set(key("A"), "Lunch")
        val auto = inbox(tx(1, "A", MessageCategory.FOOD), tx(2, "Other"))
        assertEquals(CategoryChoice.of(MessageCategory.FOOD), NicknameProfiles.profile("Lunch", names.flow.value, auto, emptySet())!!.category)
        val vague = inbox(tx(1, "A", MessageCategory.OTHERS), tx(2, "Other"))
        assertTrue(NicknameProfiles.profile("Lunch", names.flow.value, vague, emptySet())!!.category.isEmpty)
        names.set(key("B"), "Lunch")
        overrides.set(key("B"), MessageCategory.GROCERIES)
        val chosen = inbox(tx(1, "A", MessageCategory.FOOD), tx(3, "B"), tx(2, "Other"))
        assertEquals(CategoryChoice.of(MessageCategory.GROCERIES), NicknameProfiles.profile("Lunch", names.flow.value, chosen, emptySet())!!.category)
    }

    @Test fun aNewNameBringsNothing() {
        val messages = inbox(tx(1, "A"))
        val out = rename(listOf(Target(key("A"), setOf(1))), "Brand new", messages = messages)
        assertNull(out.inherited)
        assertEquals(0, out.refiled)
        assertEquals("Brand new", names.flow.value[key("A")])
    }

    @Test fun aCategoryChosenInTheSameActionWins() {
        names.set(key("A"), "Bajaj SIP")
        overrides.set(key("A"), MessageCategory.INVESTMENTS)
        val messages = inbox(tx(1, "A"), tx(2, "B"))
        val out = rename(listOf(Target(key("B"), setOf(2))), "Bajaj SIP", CategoryChoice.of(MessageCategory.SAVINGS), messages)
        assertNull(out.inherited)
        assertEquals(MessageCategory.SAVINGS, overrides.flow.value[key("B")])
    }

    @Test fun anOlderChoiceIsReplacedAndCountedForTheUndoMessage() {
        names.set(key("A"), "Bajaj SIP")
        overrides.set(key("A"), MessageCategory.INVESTMENTS)
        overrides.set(key("B"), MessageCategory.SHOPPING)
        val messages = inbox(tx(1, "A"), tx(2, "B"))
        val out = rename(listOf(Target(key("B"), setOf(2))), "Bajaj SIP", messages = messages)
        assertEquals(1, out.replacedOlder)
        assertEquals("Bajaj SIP", out.inheritedFrom)
        assertEquals(MessageCategory.INVESTMENTS, overrides.flow.value[key("B")])
        edits.undo(out.receipt)
        assertEquals(MessageCategory.SHOPPING, overrides.flow.value[key("B")])
        assertNull(names.flow.value[key("B")])
    }

    @Test fun renamingAPayeeDoesNotCountItsOwnPaymentsAsCarriers() {
        names.set(key("B"), "Old")
        overrides.set(key("B"), MessageCategory.SHOPPING)
        val messages = inbox(tx(1, "B"), tx(2, "B"))
        val out = rename(listOf(Target(key("B"), setOf(1, 2))), "Old", messages = messages)
        assertNull(out.inherited)
    }

    @Test fun undoRestoresNamesBuiltInAndCustomCategories() {
        names.set(key("X"), "Before")
        customs.assign(key("X"), sip.id)
        names.set(key("A"), "Fund")
        overrides.set(key("A"), MessageCategory.INVESTMENTS)
        val messages = inbox(tx(1, "A"), tx(2, "X"), tx(3, "Y"))
        val out = rename(listOf(Target(key("X"), setOf(2)), Target(key("Y"), setOf(3))), "Fund", messages = messages)
        assertNull(customs.state.value.assignments[key("X")])
        assertEquals(MessageCategory.INVESTMENTS, overrides.flow.value[key("X")])
        edits.undo(out.receipt)
        assertEquals("Before", names.flow.value[key("X")])
        assertEquals(sip.id, customs.state.value.assignments[key("X")])
        assertNull(overrides.flow.value[key("X")])
        assertNull(names.flow.value[key("Y")])
        assertNull(overrides.flow.value[key("Y")])
    }

    @Test fun aBulkEditNamesAndFilesEveryTargetWithACustomOrBuiltInChoice() {
        val messages = inbox(tx(1, "A"), tx(2, "B"), tx(3, "C"))
        val targets = (1L..3L).map { Target(CategoryKey.messageKey(it), setOf(it)) }
        val out = rename(targets, "Bajaj SIP", CategoryChoice.custom(sip.id), messages)
        assertEquals(3, out.renamed)
        assertEquals(3, out.refiled)
        targets.forEach { assertEquals(sip.id, customs.state.value.assignments[it.key]); assertEquals("Bajaj SIP", names.flow.value[it.key]) }
        val again = rename(targets, "Bajaj SIP", CategoryChoice.custom(sip.id), messages)
        assertFalse(again.changedAnything)
    }

    @Test fun aPictureOfTheNameIsOfferedToTargetsWithoutOne() {
        names.set(key("A"), "Fund")
        val messages = inbox(tx(1, "A"), tx(2, "B"))
        val out = edits.apply(NamingEdits.Request(listOf(Target(key("B"), setOf(2))), "Fund"), messages, setOf(key("A")))
        assertEquals(listOf(key("A") to key("B")), out.pictureCopies)
    }

    @Test fun inheritanceCanBeSwitchedOff() {
        names.set(key("A"), "Fund")
        overrides.set(key("A"), MessageCategory.INVESTMENTS)
        val messages = inbox(tx(1, "A"), tx(2, "B"))
        val out = edits.apply(NamingEdits.Request(listOf(Target(key("B"), setOf(2))), "Fund", inherit = false), messages, emptySet())
        assertNull(out.inherited)
        assertNull(overrides.flow.value[key("B")])
    }
}
