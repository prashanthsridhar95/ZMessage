package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.CustomCategoryState
import com.zyke.message.domain.categories.LegacyKeyMigration
import com.zyke.message.domain.categories.LegacyKeys
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CustomCategory
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.CustomCategoryStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules saved under a payee's older key ("mr. diy") move to the current one ("mr diy"), once, without touching any other key. */
class LegacyKeyMigrationTest {
    private val classifier = MessageClassifier()
    private val now = Instant.now()

    private fun debit(id: Long, payee: String) = classifier.classify(
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.659.00 From HDFC Bank A/C *5765 To MR DIY On 12/11/26 Ref 6123456$id\nNot You? Call 18002586161", now.toEpochMilli() - id * 1000, true)
    ).let { it.copy(content = (it.content as MessageContent.Transaction).copy(payee = payee)) }

    private class Categories : CategoryOverrideRepository {
        var writes = 0
        override val overrides = MutableStateFlow<Map<String, MessageCategory>>(emptyMap())
        override fun set(key: String, category: MessageCategory?) { overrides.value = if (category == null) overrides.value - key else overrides.value + (key to category) }
        override fun setAll(changes: Map<String, MessageCategory?>) { writes++; super.setAll(changes) }
        override fun clearAll() { overrides.value = emptyMap() }
    }

    private class Names : NicknameRepository {
        override val nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun set(key: String, name: String?) { nicknames.value = if (name.isNullOrBlank()) nicknames.value - key else nicknames.value + (key to name) }
    }

    /** Pictures the way the app's store keeps them: a key list, and the same rename rule as ImageStore.moveKeys. */
    private class Pictures : SenderImageRepository {
        override val keysWithImage = MutableStateFlow<Set<String>>(emptySet())
        override fun moveKeys(moves: Map<String, String>) {
            var keys = keysWithImage.value
            for ((from, to) in moves) if (from in keys) keys = keys - from + to
            keysWithImage.value = keys
        }
    }

    private class Fixed(private val inbox: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(inbox)
    }

    private val mrDiy = { id: Long -> debit(id, "Mr. Diy") }

    @Test
    fun mappingHoldsOnlyPayeesWhoseKeyChanged() {
        val mapping = LegacyKeys.mapping(listOf("Mr. Diy", "MR DIY", "Acme Private Limited", "Rahul Sharma", "...", "ab@ybl"))
        assertEquals(mapOf("mr. diy" to "mr diy", "acme private limited" to "acme pvt ltd"), mapping)
    }

    @Test
    fun movesTheValueToTheNewKey_newWinsOnAConflict_andOldIsDropped() {
        val mapping = mapOf("a. b" to "a b", "c. d" to "c d")
        val changes = LegacyKeys.changes(mapOf("a. b" to 1, "c. d" to 2, "c d" to 3, "x" to 9), mapping)
        assertEquals(mapOf("a b" to 1, "a. b" to null, "c. d" to null), changes)
        assertTrue(LegacyKeys.changes(mapOf("x" to 9), mapping).isEmpty())
    }

    @Test
    fun twoOldSpellingsOfOnePayee_theFirstInOrderWins() {
        val changes = LegacyKeys.changes(mapOf("a-b" to 1, "a. b" to 2), mapOf("a-b" to "a b", "a. b" to "a b"))
        assertEquals(mapOf("a b" to 1, "a-b" to null, "a. b" to null), changes)
    }

    @Test
    fun migratesCategoriesNamesFilesAndPictures_thenTheListUsesThem() {
        val categories = Categories().apply { overrides.value = mapOf("mr. diy" to MessageCategory.SHOPPING, "hdfcbk" to MessageCategory.FOOD, "msg:1" to MessageCategory.ENTERTAINMENT) }
        val names = Names().apply { nicknames.value = mapOf("mr. diy" to "DIY shop") }
        val custom = CustomCategoryStore(CustomCategoryState(listOf(CustomCategory("c1", "Hobbies", null, 0)), mapOf("mr. diy" to "c1")))
        val pictures = Pictures().apply { keysWithImage.value = setOf("mr. diy", "msg:1") }
        val migration = LegacyKeyMigration(categories, names, custom, pictures)

        val list = listOf(mrDiy(1))
        assertEquals(4, migration.run(list))

        assertEquals(mapOf("mr diy" to MessageCategory.SHOPPING, "hdfcbk" to MessageCategory.FOOD, "msg:1" to MessageCategory.ENTERTAINMENT), categories.overrides.value)
        assertEquals(mapOf("mr diy" to "DIY shop"), names.nicknames.value)
        assertEquals(mapOf("mr diy" to "c1"), custom.state.value.assignments)
        assertEquals(setOf("mr diy", "msg:1"), pictures.keysWithImage.value)

        val out = runBlocking {
            ObserveMessagesUseCase(Fixed(listOf(list[0].message)), classifier, categoryOverrides = categories, nicknameRepository = names, senderImages = pictures, customCategories = custom).invoke().first()
        }
        assertEquals("mr diy", CategoryKey.of(out[0]))
    }

    @Test
    fun isIdempotent_andCheapWhenNothingIsStoredUnderAnOldKey() {
        val categories = Categories().apply { overrides.value = mapOf("mr. diy" to MessageCategory.SHOPPING) }
        val migration = LegacyKeyMigration(categories, Names(), CustomCategoryStore(), Pictures())
        val list = listOf(mrDiy(1), mrDiy(2))
        assertEquals(1, migration.run(list))
        val after = categories.overrides.value
        assertEquals(0, migration.run(list))
        assertEquals(1, categories.writes)
        // A fresh runner (the next start) finds nothing left to move.
        assertEquals(0, LegacyKeyMigration(categories, Names(), CustomCategoryStore(), Pictures()).run(list))
        assertEquals(after, categories.overrides.value)
        assertEquals(1, categories.writes)
    }

    @Test
    fun aKeyOfASenderIsNeverRewritten() {
        // The sender's own key may look like a payee's old spelling; no message has a payee that produces it, so it stays.
        val categories = Categories().apply { overrides.value = mapOf("some. sender" to MessageCategory.FOOD, "mr. diy" to MessageCategory.SHOPPING) }
        LegacyKeyMigration(categories, Names(), CustomCategoryStore(), Pictures()).run(listOf(mrDiy(1)))
        assertEquals(MessageCategory.FOOD, categories.overrides.value["some. sender"])
        assertNull(categories.overrides.value["mr. diy"])
    }

    @Test
    fun payeesFirstSeenInALaterListAreMigratedThen() {
        val categories = Categories().apply { overrides.value = mapOf("mr. diy" to MessageCategory.SHOPPING, "acme private limited" to MessageCategory.UTILITIES) }
        val migration = LegacyKeyMigration(categories, Names(), CustomCategoryStore(), Pictures())
        migration.run(listOf(mrDiy(1)))
        assertEquals(MessageCategory.UTILITIES, categories.overrides.value["acme private limited"])
        migration.run(listOf(mrDiy(1), debit(2, "Acme Private Limited")))
        assertEquals(MessageCategory.UTILITIES, categories.overrides.value["acme pvt ltd"])
        assertNull(categories.overrides.value["acme private limited"])
    }

    @Test
    fun theUseCaseMigratesOnTheFirstList_andTheRuleShows() {
        val inbox = listOf(SmsMessage(1, "AD-HDFCBK", "Sent Rs.659.00 From HDFC Bank A/C *5765 To Acme Pvt Ltd On 12/11/26 Ref 61234561\nNot You? Call 18002586161", now.toEpochMilli() - 1000, true))
        val payee = (classifier.classify(inbox[0]).content as MessageContent.Transaction).payee
        val old = CategoryKey.normalise(payee)
        val key = CategoryKey.of(classifier.classify(inbox[0]))!!
        // When the parser keeps the name's punctuation the key changed; otherwise there is nothing to move and the rule already applies.
        val categories = Categories().apply { overrides.value = mapOf(old to MessageCategory.SHOPPING) }
        val migration = LegacyKeyMigration(categories, Names(), CustomCategoryStore(), Pictures())
        val out = runBlocking { ObserveMessagesUseCase(Fixed(inbox), classifier, categoryOverrides = categories, legacyKeys = migration).invoke().first() }
        assertEquals(MessageCategory.SHOPPING, out[0].category)
        assertEquals(setOf(key), categories.overrides.value.keys)
    }
}
