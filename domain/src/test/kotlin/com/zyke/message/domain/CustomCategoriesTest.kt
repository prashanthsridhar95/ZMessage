package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.CustomCategories
import com.zyke.message.domain.categories.CustomCategoryState
import com.zyke.message.domain.categories.NameCheck
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.SpendSummaryBuilder
import com.zyke.message.domain.model.CustomCategory
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.CustomCategoryRepository
import com.zyke.message.domain.repository.CustomCategoryStore
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCategoriesTest {
    private val gym = CustomCategory("c1", "Gym", "🏋️", 2)

    private class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    private fun debit(id: Long, to: String = "SOMEONE ELSE", amount: String = "540.00") =
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank A/C *5765\nTo $to\nOn 30/10/26\nRef $id", 1_793_000_000_000L + id, true)

    private fun read(inbox: List<SmsMessage>, store: CustomCategoryStore) = runBlocking {
        ObserveMessagesUseCase(Sms(inbox), MessageClassifier(), Dispatchers.Unconfined, customCategories = store)().first()
    }

    @Test
    fun names_areTrimmed_boundedAndUniqueIgnoringCase() {
        assertEquals(NameCheck.Valid("Pet care"), CustomCategories.validate("  Pet   care ", emptyList()))
        assertTrue(CustomCategories.validate("   ", emptyList()) is NameCheck.Invalid)
        assertTrue(CustomCategories.validate("x".repeat(25), emptyList()) is NameCheck.Invalid)
        assertTrue(CustomCategories.validate("x".repeat(24), emptyList()) is NameCheck.Valid)
        assertTrue(CustomCategories.validate("a\u0007b", emptyList()) is NameCheck.Invalid)
        // Built-in names, however they are written, and other custom ones are taken.
        assertTrue(CustomCategories.validate("food", emptyList()) is NameCheck.Invalid)
        assertTrue(CustomCategories.validate(" FAMILY & gifts", emptyList()) is NameCheck.Invalid)
        assertTrue(CustomCategories.validate("upi payment", emptyList()) is NameCheck.Invalid)
        assertTrue(CustomCategories.validate("gYm", listOf(gym)) is NameCheck.Invalid)
        // Renaming a category to itself, or changing only its case, is allowed.
        assertTrue(CustomCategories.validate("GYM", listOf(gym), ignoreId = "c1") is NameCheck.Valid)
    }

    @Test
    fun theStore_limitsTheCount_andGivesUniqueIds() {
        val store = CustomCategoryStore()
        repeat(CustomCategories.MAX_COUNT) { assertTrue(store.add("Cat $it", null, it) is CustomCategoryRepository.Result.Created) }
        assertTrue(store.add("One more", null, 0) is CustomCategoryRepository.Result.Rejected)
        assertEquals(CustomCategories.MAX_COUNT, store.state.value.categories.map { it.id }.toSet().size)
        assertTrue(store.state.value.categories.all { it.colourIndex in 0 until CustomCategories.COLOUR_COUNT })
    }

    @Test
    fun aCustomCategory_isAppliedToOnePaymentOrToEverythingFromAReceiver_andLabelledOneWay() {
        val store = CustomCategoryStore()
        val made = (store.add("Gym", "🏋️", 2) as CustomCategoryRepository.Result.Created).category
        val inbox = listOf(debit(1, "FITLIFE"), debit(2, "FITLIFE"), debit(3, "CAFE"))
        val plain = read(inbox, store)
        store.assign(CategoryKey.messageKey(3), made.id)
        store.assign(CategoryKey.of(plain.first { it.id == 1L })!!, made.id)
        val filed = read(inbox, store).associateBy { it.id }

        assertEquals(made.id, filed.getValue(1).customCategoryId)
        assertEquals(made.id, filed.getValue(2).customCategoryId)
        assertEquals(made.id, filed.getValue(3).customCategoryId)
        assertEquals(MessageCategory.OTHERS, filed.getValue(1).category)
        assertTrue(filed.getValue(3).categoryOwn && !filed.getValue(1).categoryOwn)
        val look = CustomCategories.look(filed.getValue(1), store.state.value.categories)
        assertEquals("Gym", look.name); assertEquals(2, look.colourIndex); assertTrue(look.custom)
        assertEquals("Others", CustomCategories.look(MessageCategory.OTHERS, null, emptyList()).name)
        // A message with a category that was since deleted reads as the built-in one.
        assertEquals("Others", CustomCategories.look(filed.getValue(1).category, "gone", store.state.value.categories).name)
    }

    @Test
    fun aPaymentsOwnBuiltInChoice_beatsItsSendersCustomOne() {
        val state = CustomCategoryState(listOf(gym), mapOf("fitlife" to "c1"))
        val msgs = read(listOf(debit(1, "FITLIFE")), CustomCategoryStore()).map { it.copy(categoryOwn = true, category = MessageCategory.FOOD, categoryOverridden = true) }
        assertEquals(MessageCategory.FOOD, CustomCategories.apply(msgs, state).single().category)
        assertNull(CustomCategories.apply(msgs, state).single().customCategoryId)
    }

    @Test
    fun nothingFiled_keepsEveryInstance() {
        val msgs = read(listOf(debit(1)), CustomCategoryStore())
        assertSame(msgs, CustomCategories.apply(msgs, CustomCategoryState(listOf(gym))))
    }

    @Test
    fun deleting_putsEverythingBackToAutomatic_andUndoRestoresItInPlace() {
        val store = CustomCategoryStore(newId = { "c${it.size + 1}" })
        val a = (store.add("Gym", null, 0) as CustomCategoryRepository.Result.Created).category
        val b = (store.add("Pets", null, 1) as CustomCategoryRepository.Result.Created).category
        store.assign("k1", a.id); store.assign("msg:7", a.id); store.assign("k2", b.id)
        val inbox = listOf(debit(7, "CAFE"))
        assertEquals(a.id, read(inbox, store).single().customCategoryId)

        val deleted = store.delete(a.id)!!
        assertEquals(setOf("k1", "msg:7"), deleted.keys)
        assertEquals(mapOf("k2" to b.id), store.state.value.assignments)
        val after = read(inbox, store).single()
        assertNull(after.customCategoryId)

        store.restore(deleted)
        assertEquals(listOf("Gym", "Pets"), store.state.value.categories.map { it.name })
        assertEquals(a.id, read(inbox, store).single().customCategoryId)
        assertNull(store.delete("nope"))
    }

    @Test
    fun assigningToAMissingCategory_isIgnored_andClearingWorks() {
        val store = CustomCategoryStore()
        store.assign("k", "ghost")
        assertTrue(store.state.value.assignments.isEmpty())
        val c = (store.add("Gym", null, 0) as CustomCategoryRepository.Result.Created).category
        store.assign("k", c.id); store.assign("k", null)
        assertTrue(store.state.value.assignments.isEmpty())
    }

    @Test
    fun renaming_keepsTheFiling_andRefusesATakenName() {
        val store = CustomCategoryStore()
        val a = (store.add("Gym", null, 0) as CustomCategoryRepository.Result.Created).category
        store.add("Pets", null, 1)
        store.assign("k", a.id)
        assertTrue(store.edit(a.id, "Pets", null, 0) is NameCheck.Invalid)
        assertEquals(NameCheck.Valid("Fitness"), store.edit(a.id, " Fitness ", "⭐", 9))
        assertEquals("Fitness", store.state.value.categories.first().name)
        assertEquals(1, store.state.value.categories.first().colourIndex)
        assertEquals(a.id, store.state.value.assignments["k"])
    }

    @Test
    fun storedText_roundTrips_andOldOrDamagedStorageReadsAsNothing() {
        val odd = CustomCategory("c9", "Tab\there \\ new", null, 7)
        val text = CustomCategories.encodeCategories(listOf(gym, odd.copy(name = "A B")))
        assertEquals(listOf(gym, odd.copy(name = "A B")), CustomCategories.decodeCategories(text))
        val assignments = mapOf("fit life" to "c1", "msg:5" to "c9", "we\\ird\tkey" to "c1")
        assertEquals(assignments, CustomCategories.decodeAssignments(CustomCategories.encodeAssignments(assignments)))
        // A build before this feature stored nothing; damaged lines are skipped.
        assertEquals(emptyList<CustomCategory>(), CustomCategories.decodeCategories(null))
        assertEquals(emptyMap<String, String>(), CustomCategories.decodeAssignments(""))
        assertEquals(listOf(gym), CustomCategories.decodeCategories(CustomCategories.encodeCategories(listOf(gym)) + "\ngarbage\n\t\t\t"))
    }

    @Test
    fun theSpendingBreakdown_listsACustomCategoryOnItsOwn() {
        val store = CustomCategoryStore()
        val made = (store.add("Gym", null, 0) as CustomCategoryRepository.Result.Created).category
        store.assign(CategoryKey.messageKey(1), made.id)
        val now = Instant.ofEpochMilli(1_793_000_000_000L)
        val summary = SpendSummaryBuilder.build(read(listOf(debit(1, amount = "100.00"), debit(2, amount = "50.00")), store), now, ZoneId.of("UTC"))!!
        assertTrue(summary.byCategory.any { it.customCategoryId == made.id && it.amount.toInt() == 100 })
        assertTrue(summary.byCategory.none { it.customCategoryId == null && it.amount.toInt() == 150 })
    }
}
