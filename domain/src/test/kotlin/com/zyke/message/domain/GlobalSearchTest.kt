package com.zyke.message.domain

import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.ledger.ManualEntry
import com.zyke.message.domain.ledger.ManualKind
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.search.GlobalSearch
import com.zyke.message.domain.search.SearchKind
import com.zyke.message.domain.search.SearchSources
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {
    private fun tx(id: Long, payee: String, amount: String, category: MessageCategory = MessageCategory.FOOD, date: Long = id) =
        ClassifiedMessage(
            message = SmsMessage(id, "AD-BANK", "Rs.$amount paid to $payee", date, true),
            category = category,
            merchant = Merchant(payee, MerchantLogo.PERSON, category),
            content = MessageContent.Transaction(BankName.OTHERS, payee, amount, "1234", isReceived = false, isCard = false)
        )

    private fun hits(query: String, sources: SearchSources, kind: SearchKind) =
        GlobalSearch.search(query, sources).groups.firstOrNull { it.kind == kind }?.hits.orEmpty()

    @Test fun blankQueryFindsNothing() {
        assertTrue(GlobalSearch.search("  ", SearchSources(messages = listOf(tx(1, "Swiggy", "540")))).isEmpty)
    }

    @Test fun nameMatchesRankExactThenPrefixThenContains() {
        val sources = SearchSources(messages = listOf(tx(1, "Big Swiggy Store", "10"), tx(2, "Swiggy Instamart", "10"), tx(3, "Swiggy", "10")))
        assertEquals(listOf(3L, 2L, 1L), hits("swiggy", sources, SearchKind.MESSAGE).map { it.messageId })
    }

    @Test fun amountTypedPlainOrWithRupeeSignFindsPayments() {
        val sources = SearchSources(messages = listOf(tx(1, "Zomato", "540"), tx(2, "Uber", "1,200.00"), tx(3, "Cafe", "99")))
        assertEquals(listOf(1L), hits("540", sources, SearchKind.MESSAGE).map { it.messageId })
        assertEquals(listOf(2L), hits("₹1,200", sources, SearchKind.MESSAGE).map { it.messageId })
        assertEquals(listOf(2L), hits("Rs 1200", sources, SearchKind.MESSAGE).map { it.messageId })
    }

    @Test fun exactAmountBeatsPrefix() {
        val sources = SearchSources(messages = listOf(tx(1, "A", "5400"), tx(2, "B", "540")))
        assertEquals(listOf(2L, 1L), hits("540", sources, SearchKind.MESSAGE).map { it.messageId })
    }

    @Test fun categoryNameFindsMessages() {
        val sources = SearchSources(messages = listOf(tx(1, "Shell", "300", MessageCategory.FUEL), tx(2, "Cafe", "30")))
        assertEquals(listOf(1L), hits("fuel", sources, SearchKind.MESSAGE).map { it.messageId })
    }

    @Test fun resultsAreLimitedPerGroupButTotalKept() {
        val many = (1L..20L).map { tx(it, "Swiggy $it", "10") }
        val group = GlobalSearch.search("swiggy", SearchSources(messages = many), limit = 3).groups.single()
        assertEquals(3, group.hits.size)
        assertEquals(20, group.total)
    }

    @Test fun handMadeEntriesAreAGroupOfTheirOwn() {
        val manual = ManualEntries.toMessage(
            ManualEntry(7, ManualKind.EXPENSE, BigDecimal("250"), "Tea stall", MessageCategory.FOOD, LocalDate.of(2026, 10, 1)),
            ZoneId.of("UTC")
        )
        val sources = SearchSources(messages = listOf(manual, tx(1, "Tea House", "50")))
        assertEquals(listOf(-7L), hits("tea", sources, SearchKind.HAND_MADE).map { it.messageId })
        assertEquals(listOf(1L), hits("tea", sources, SearchKind.MESSAGE).map { it.messageId })
    }

    @Test fun goalsRemindersAndStocksAreFound() {
        val sources = SearchSources(
            goals = listOf(Goal("g", "Goa trip", BigDecimal("50000"))),
            reminders = listOf(Reminder("r", "Pay rent", BigDecimal("15000"), LocalDate.of(2026, 11, 1))),
            watched = listOf(WatchSymbol("RELIANCE.NS", "Reliance"))
        )
        assertEquals("g", hits("goa", sources, SearchKind.GOAL).single().id)
        assertEquals("r", hits("15000", sources, SearchKind.REMINDER).single().id)
        assertEquals("RELIANCE.NS", hits("reli", sources, SearchKind.STOCK).single().id)
        assertEquals("RELIANCE.NS", hits("industries", sources, SearchKind.STOCK).single().id)
        assertNull(GlobalSearch.search("zzzz", sources).groups.firstOrNull())
    }
}
