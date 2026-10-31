package com.zyke.message.domain

import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.search.GlobalSearch
import com.zyke.message.domain.search.RecentSearches
import com.zyke.message.domain.search.SearchChipKind
import com.zyke.message.domain.search.SearchKind
import com.zyke.message.domain.search.SearchQueryParser
import com.zyke.message.domain.search.SearchScope
import com.zyke.message.domain.search.SearchSources
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRevampTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 31)
    private fun millis(date: LocalDate) = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun tx(
        id: Long, payee: String, amount: String, date: LocalDate = today, category: MessageCategory = MessageCategory.FOOD,
        received: Boolean = false, tags: List<String> = emptyList(), note: String? = null
    ) = ClassifiedMessage(
        message = SmsMessage(id, "AD-BANK", "Rs.$amount paid to $payee", millis(date), true),
        category = category,
        merchant = Merchant(payee, MerchantLogo.PERSON, category),
        content = MessageContent.Transaction(BankName.OTHERS, payee, amount, "1234", isReceived = received, isCard = false),
        note = note, tags = tags
    )

    private fun search(q: String, vararg m: ClassifiedMessage, scope: SearchScope = SearchScope.ALL) =
        GlobalSearch.search(q, SearchSources(messages = m.toList()), 50, scope, today, zone)

    private fun ids(q: String, vararg m: ClassifiedMessage) =
        search(q, *m).groups.first { it.kind == SearchKind.MESSAGE }.hits.map { it.messageId }

    // parser
    @Test fun parsesTimeCategoryAndAmountPhrases() {
        val p = SearchQueryParser.parse("swiggy last month", today)
        assertEquals("swiggy", p.text)
        assertEquals(LocalDate.of(2026, 9, 1), p.from)
        assertEquals(LocalDate.of(2026, 9, 30), p.to)
        assertEquals(listOf(SearchChipKind.TIME), p.chips.map { it.kind })

        val q = SearchQueryParser.parse("food this month >5k", today)
        assertEquals(MessageCategoryFood, q.category)
        assertEquals(LocalDate.of(2026, 10, 1), q.from)
        assertEquals(BigDecimal(5000), q.minAmount)
        assertEquals("", q.text)
        assertEquals(3, q.chips.size)
    }

    @Test fun amountWordsAndShorthand() {
        assertEquals(BigDecimal(2000), SearchQueryParser.parse("salary under 2k", today).maxAmount)
        assertEquals(0, BigDecimal(150000).compareTo(SearchQueryParser.parse("over 1.5l", today).minAmount))
        assertEquals(BigDecimal(5000), SearchQueryParser.parse(">₹5,000", today).minAmount)
        val plain = SearchQueryParser.parse("5000", today)
        assertFalse(plain.hasFilters)
        assertEquals("5000", plain.text)
        assertNull(SearchQueryParser.amount("abc"))
        assertNull(SearchQueryParser.amount("1.2.3"))
    }

    @Test fun removingAChipRebuildsTheQuery() {
        val p = SearchQueryParser.parse("swiggy last month >500", today)
        val time = p.chips.first { it.kind == SearchChipKind.TIME }
        assertEquals("swiggy >500", p.without(time))
        assertEquals("swiggy last month", p.without(p.chips.first { it.kind == SearchChipKind.AMOUNT }))
    }

    @Test fun typeWordBecomesAChipAndFillerIsDropped() {
        val p = SearchQueryParser.parse("show my bills for hdfc", today)
        assertEquals(SmartFilter.BILLS, p.smart)
        assertEquals("hdfc", p.text)
    }

    // search
    @Test fun timeSpanAndAmountFilterPayments() {
        val oct = tx(1, "Swiggy", "300", today.minusDays(3))
        val sep = tx(2, "Swiggy", "800", LocalDate.of(2026, 9, 12))
        val big = tx(3, "Zara", "9,000", today, MessageCategory.SHOPPING)
        assertEquals(listOf(2L), ids("swiggy last month", oct, sep, big))
        assertEquals(listOf(1L, 3L).toSet(), ids("this month", oct, sep, big).toSet())
        assertEquals(listOf(3L), ids(">5000", oct, sep, big))
        assertEquals(listOf(1L), ids("food this month under 500", oct, sep, big))
    }

    @Test fun filtersThatMatchNothingFallBackToPlainWords() {
        val m = tx(1, "Food Court", "100", category = MessageCategory.SHOPPING)
        assertEquals(listOf(1L), ids("food", m))
    }

    @Test fun scopeNarrowsToOneKind() {
        val m = tx(1, "Swiggy", "300", tags = listOf("swig-trip"))
        val all = search("swig", m)
        assertTrue(all.groups.any { it.kind == SearchKind.MESSAGE } && all.groups.any { it.kind == SearchKind.TAG })
        assertEquals(listOf(SearchKind.TAG), search("swig", m, scope = SearchScope.TAGS).groups.map { it.kind })
        assertTrue(search("swig", m, scope = SearchScope.ACCOUNTS).isEmpty)
    }

    @Test fun paymentHitsCarryWhatARichRowNeeds() {
        val hit = search("zomato", tx(1, "Zomato", "250", received = true)).groups.first().hits.single()
        assertEquals("250", hit.amount)
        assertTrue(hit.credit)
        assertEquals(MerchantLogo.PERSON, hit.logo)
        assertEquals("Food", hit.category?.let { "Food" })
    }

    @Test fun tagsAndNotesAreFoundAndCounted() {
        val a = tx(1, "A", "1", tags = listOf("trip"), note = "hotel booking")
        val b = tx(2, "B", "2", tags = listOf("trip"))
        val tag = search("trip", a, b, scope = SearchScope.TAGS).groups.single().hits
        assertEquals("2 payments", tag.single { it.id == "tag-trip" }.subtitle)
        assertEquals(1L, search("hotel", a, b, scope = SearchScope.TAGS).groups.single().hits.single().messageId)
    }

    @Test fun pagesAreFoundAndASpokenPageComesFirst() {
        val r = GlobalSearch.search("settings", SearchSources(messages = listOf(tx(1, "My settings shop", "5"))), 5, today = today, zone = zone)
        assertEquals(SearchKind.PAGE, r.groups.first().kind)
        assertEquals("settings", r.groups.first().hits.first().id)
        val dark = GlobalSearch.search("dark", SearchSources(), 5, today = today, zone = zone)
        assertEquals("settings", dark.groups.single().hits.single().id)
    }

    @Test fun billsScopeIncludesReminders() {
        val reminder = Reminder("r", "Rent", BigDecimal("15000"), LocalDate.of(2026, 11, 1))
        val r = GlobalSearch.search("rent", SearchSources(reminders = listOf(reminder)), 5, SearchScope.BILLS, today, zone)
        assertEquals(SearchKind.REMINDER, r.groups.single().kind)
    }

    // recents
    @Test fun recentsAreNewestFirstDedupedAndCapped() {
        var list = emptyList<String>()
        for (q in listOf("a1", "b1", "c1")) list = RecentSearches.add(list, q)
        assertEquals(listOf("c1", "b1", "a1"), list)
        assertEquals(listOf("A1", "c1", "b1"), RecentSearches.add(list, "  A1 "))
        for (i in 1..20) list = RecentSearches.add(list, "query $i")
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("query 20", list.first())
        assertEquals(list, RecentSearches.add(list, "x"))
        assertEquals(list.drop(1), RecentSearches.remove(list, "QUERY 20"))
    }

    private companion object {
        val MessageCategoryFood = MessageCategory.FOOD
    }
}
