package com.zyke.message.domain

import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.nickname.NicknameSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NicknameSuggestionsTest {
    private val names = mapOf(
        "k1" to "Swiggy", "k2" to "Swiggy", "k3" to "Swiggy Instamart", "k4" to "Lunch place", "k5" to "My landlord", "k6" to "Ravi"
    )

    @Test
    fun bestMatchesComeFirst_thenWorse_andMostUsedBreaksTies() {
        val found = NicknameSuggestions.suggest("swi", names).map { it.name }
        assertEquals(listOf("Swiggy", "Swiggy Instamart"), found)

        // A word start beats a plain contains; letters in order come last.
        assertEquals(listOf("Lunch place", "My landlord"), NicknameSuggestions.suggest("l", names).map { it.name })
        assertEquals(listOf("Swiggy", "Swiggy Instamart"), NicknameSuggestions.suggest("swgy", names).map { it.name })
    }

    @Test
    fun nothingTypedOffersTheMostUsed_andAFullMatchIsNotOfferedBack() {
        assertEquals("Swiggy", NicknameSuggestions.suggest("", names).first().name)
        assertEquals(2, NicknameSuggestions.suggest("", names).first().uses)
        assertEquals(listOf("Swiggy Instamart"), NicknameSuggestions.suggest("swiggy", names).map { it.name })
        assertEquals(emptyList<Any>(), NicknameSuggestions.suggest("zzz", names))
    }

    @Test
    fun aNameAlreadyUsedCarriesItsCategoryAndPicture_evenFromDifferentHolders() {
        // One holder has the category, another the picture: the new thing gets both.
        val both = NicknameSuggestions.inherit("swiggy", "k9", names, mapOf("k1" to MessageCategory.FOOD), setOf("k2"))!!
        assertEquals(MessageCategory.FOOD, both.category)
        assertEquals("k2", both.imageKey)

        // The category most holders share wins.
        val shared = NicknameSuggestions.inherit("Swiggy", "k9", names, mapOf("k1" to MessageCategory.FOOD, "k2" to MessageCategory.FOOD), emptySet())!!
        assertEquals(MessageCategory.FOOD, shared.category)
        assertNull(shared.imageKey)
    }

    @Test
    fun nothingIsInheritedFromANewNameOrFromItself() {
        assertNull(NicknameSuggestions.inherit("Brand new", "k9", names, mapOf("k1" to MessageCategory.FOOD), setOf("k1")))
        assertNull(NicknameSuggestions.inherit("Ravi", "k6", names, mapOf("k6" to MessageCategory.FAMILY), setOf("k6")))
        assertNull(NicknameSuggestions.inherit("Swiggy", "k9", names, emptyMap(), emptySet()))
    }
}
