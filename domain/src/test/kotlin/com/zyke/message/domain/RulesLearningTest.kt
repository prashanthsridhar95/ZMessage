package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.Rules
import com.zyke.message.domain.categories.SimilarSenders
import com.zyke.message.domain.categories.Tokens
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesLearningTest {
    private val c = MessageClassifier()
    private fun pay(id: Long, to: String) =
        c.classify(SmsMessage(id, "AD-HDFCBK", "Sent Rs.100.00\nFrom HDFC Bank A/C *5765\nTo $to\nOn 26/10/26\nRef $id", id * 1000, true))

    private val instamart = pay(1, "Swiggy Instamart")
    private val genie = pay(2, "Swiggy Genie")
    private val genie2 = pay(3, "Swiggy Genie")
    private val other = pay(4, "Raju Tea Stall")
    private val all = listOf(instamart, genie, genie2, other)

    @Test fun similar_sharesAWord() {
        val found = SimilarSenders.find(instamart, all, emptyMap())
        assertEquals(listOf("swiggy genie"), found.map { it.key })
        assertEquals(2, found.single().messageCount)
    }

    @Test fun similar_differentWordsAreNotOffered() {
        assertTrue(SimilarSenders.find(other, all, emptyMap()).isEmpty())
    }

    @Test fun similar_alreadyFiledAreExcluded() {
        val filed = mapOf(CategoryKey.of(genie)!! to MessageCategory.SHOPPING)
        assertTrue(SimilarSenders.find(instamart, all, filed).isEmpty())
        val single = mapOf(CategoryKey.messageKey(2) to MessageCategory.FOOD, CategoryKey.messageKey(3) to MessageCategory.FOOD)
        assertTrue(SimilarSenders.find(instamart, all, single).isEmpty())
    }

    @Test fun describe_listsRulesSinglesAndCounts() {
        val k = CategoryKey.of(genie)!!
        val o = mapOf(k to MessageCategory.FOOD, CategoryKey.messageKey(4) to MessageCategory.OTHERS, "gone payee" to MessageCategory.FUEL)
        val view = Rules.describe(all, o, mapOf(k to "Genie"), setOf(k), mapOf("swiggy" to MessageCategory.FOOD))
        val rule = view.rules.first { it.key == k }
        assertEquals("Swiggy Genie", rule.label)
        assertEquals(2, rule.affects)
        assertEquals("Genie", rule.nickname)
        assertTrue(rule.hasPicture)
        assertEquals(Rules.UNKNOWN_PAYEE, view.rules.first { it.key == "gone payee" }.label)
        assertEquals(0, view.rules.first { it.key == "gone payee" }.affects)
        assertEquals(1, view.singles.single().affects)
        assertEquals("Raju Tea Stall", view.singles.single().label)
        // swiggy rule reaches only the unfiled Instamart; Genie has its own choice.
        assertEquals(1, view.tokenRules.single().affects)
    }

    @Test fun tokenRule_matchesAndIsWeakerThanExactChoices() {
        val words = mapOf("swiggy" to MessageCategory.FOOD)
        assertEquals(MessageCategory.FOOD, CategoryKey.apply(instamart, emptyMap(), words).category)
        assertTrue(CategoryKey.apply(instamart, emptyMap(), words).categoryOverridden)
        assertEquals(other.category, CategoryKey.apply(other, emptyMap(), words).category)
        val sender = mapOf(CategoryKey.of(instamart)!! to MessageCategory.SHOPPING)
        assertEquals(MessageCategory.SHOPPING, CategoryKey.apply(instamart, sender, words).category)
        val own = sender + (CategoryKey.messageKey(1) to MessageCategory.FUEL)
        assertEquals(MessageCategory.FUEL, CategoryKey.apply(instamart, own, words).category)
        assertNull(Tokens.match("msg:12", words))
    }

    @Test fun suggestion_needsThreeDistinctReceiversWithOneCategory() {
        val f = MessageCategory.FOOD
        val two = mapOf("swiggy a" to f, "swiggy b" to f)
        assertTrue(Rules.suggestTokenRules(two, emptyMap()).isEmpty())
        val three = two + ("swiggy c" to f)
        val s = Rules.suggestTokenRules(three, emptyMap()).single()
        assertEquals("swiggy", s.token)
        assertEquals(3, s.receivers)
        assertTrue(Rules.suggestTokenRules(three, mapOf("swiggy" to f)).isEmpty())
        assertTrue(Rules.suggestTokenRules(three + ("swiggy d" to MessageCategory.SHOPPING) + ("swiggy e" to MessageCategory.SHOPPING) + ("swiggy f" to MessageCategory.SHOPPING), emptyMap()).isEmpty())
        assertFalse(Rules.suggestTokenRules(mapOf("msg:1" to f, "msg:2" to f, "msg:3" to f), emptyMap()).isNotEmpty())
    }
}
