package com.zyke.message.domain

import com.zyke.message.domain.insights.RecurringGroup
import com.zyke.message.domain.insights.RecurringGroups
import com.zyke.message.domain.insights.RecurringListItem
import com.zyke.message.domain.insights.RecurringMerge
import com.zyke.message.domain.insights.RecurringMerges
import com.zyke.message.domain.insights.RecurringNames
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.insights.SubscriptionOverview
import com.zyke.message.domain.insights.SubscriptionState
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringGroupsTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val due = LocalDate.of(2026, 11, 5)

    private fun sub(payee: String, ordinal: Int = 1, of: Int = 1, amount: String = "2500", next: LocalDate = due, id: Long = 1) =
        Subscription(payee, BigDecimal(amount), next.minusMonths(1), next, 6, id, variant = if (of > 1) "v$ordinal" else "", ordinal = ordinal, ofCount = of)

    private val icc = (1..4).map { sub("Indian Clearing Corp", it, 4, id = it.toLong()) }
    private val funds = listOf(
        sub("Mahindra Manulife Manufacturing Fund Reg", id = 11),
        sub("Mahindra Manulife Multi Cap-Reg-Growth", id = 12),
        sub("Mahindra Manulife Value Fund Reg Growth", id = 13)
    )
    private val netflix = sub("Netflix", amount = "649", next = due.plusDays(9), id = 20)
    private val all = icc + funds + listOf(netflix)

    private fun merged(): List<Subscription> =
        RecurringMerges.apply(all, (0..2).map { RecurringMerge(funds[it].key, listOf(icc[it].key), null, 1) })

    private val sip = RecurringGroup("g1", "Mahindra Manulife Fund", emptyList())

    @Test fun threeMergedAndOneUnmergedMakeOneGroup() {
        val base = merged()
        val g = sip.copy(memberKeys = listOf(funds[0].key, funds[1].key, funds[2].key, icc[3].key))
        val rows = RecurringGroups.apply(base, listOf(g))
        assertEquals(2, rows.size)
        val group = rows.filterIsInstance<RecurringListItem.Group<Subscription>>().single()
        assertEquals(4, group.count)
        assertEquals(BigDecimal("10000.00"), group.monthly)
        assertEquals(BigDecimal(120000), group.yearly)
        assertEquals(4, group.activeCount)
        assertEquals("", group.stateSummary)
        assertEquals("group:g1", group.listKey)
        // Display only: the same individual entries still add up elsewhere.
        val overview = SubscriptionOverview.build(base, emptyMap(), emptyMap(), today)
        assertEquals(BigDecimal(120000 + 649 * 12), overview.yearlyTotal)
        assertEquals(base.size, rows.sumOf { if (it is RecurringListItem.Group) it.count else 1 })
    }

    @Test fun mergedMemberFoundThroughMergedKeys() {
        val base = merged()
        // Stored by a key that is now only a merged name.
        val g = sip.copy(memberKeys = listOf(icc[0].key, icc[1].key))
        val group = RecurringGroups.apply(base, listOf(g)).filterIsInstance<RecurringListItem.Group<Subscription>>().single()
        assertEquals(listOf(funds[0].key, funds[1].key), group.members.map { it.key })
    }

    @Test fun orderIsPositionOfFirstMember() {
        val base = listOf(netflix, funds[0], icc[3], funds[1])
        val rows = RecurringGroups.apply(base, listOf(sip.copy(memberKeys = listOf(funds[1].key, funds[0].key, icc[3].key))))
        assertEquals(listOf(netflix.key, "group:g1"), rows.map { it.listKey })
        val group = rows[1] as RecurringListItem.Group
        assertEquals(listOf(funds[0].key, icc[3].key, funds[1].key), group.members.map { it.key })
    }

    @Test fun vanishedMembersDropAndEmptyOrSingleGroupIsPlain() {
        val base = listOf(netflix, funds[0], funds[1])
        val three = sip.copy(memberKeys = listOf(funds[0].key, funds[1].key, "gone-key"))
        assertEquals(2, (RecurringGroups.apply(base, listOf(three)).last() as RecurringListItem.Group).count)
        val one = sip.copy(memberKeys = listOf(funds[0].key, "gone-key"))
        assertTrue(RecurringGroups.apply(base, listOf(one)).all { it is RecurringListItem.Single })
        assertTrue(RecurringGroups.apply(base, listOf(sip.copy(memberKeys = listOf("gone")))).all { it is RecurringListItem.Single })
        assertEquals(3, RecurringGroups.apply(base, listOf(one)).size)
        // It returns when a second member appears.
        assertTrue(RecurringGroups.apply(base, listOf(one.copy(memberKeys = one.memberKeys + funds[1].key))).any { it is RecurringListItem.Group })
    }

    @Test fun entryInTwoGroupsFirstWins() {
        val a = RecurringGroup("a", "A", listOf(funds[0].key, funds[1].key))
        val b = RecurringGroup("b", "B", listOf(funds[1].key, funds[2].key))
        val rows = RecurringGroups.apply(funds, listOf(a, b))
        val ga = rows.filterIsInstance<RecurringListItem.Group<Subscription>>().single()
        assertEquals("A", ga.name)
        assertEquals(1, rows.count { it is RecurringListItem.Single && it.subscription.key == funds[2].key })
    }

    @Test fun movingBetweenGroupsRemovesFromTheOld() {
        var groups = RecurringGroups.created(emptyList(), "a", "A", listOf("k1", "k2", "k3"), 1)
        groups = RecurringGroups.created(groups, "b", "B", listOf("k3", "k4"), 2)
        assertEquals(listOf("k1", "k2"), groups.first { it.id == "a" }.memberKeys)
        groups = RecurringGroups.added(groups, "a", listOf("k4"))
        assertEquals(listOf("k3"), groups.first { it.id == "b" }.memberKeys)
        groups = RecurringGroups.removed(groups, listOf("k3"))
        assertTrue(groups.none { it.id == "b" })
        assertEquals(listOf("k1", "k2", "k4"), groups.single().memberKeys)
        assertTrue(RecurringGroups.ungrouped(groups, "a").isEmpty())
        assertEquals("Z", RecurringGroups.renamed(groups, "a", " Z ").single().name)
        assertEquals("A", RecurringGroups.renamed(groups, "a", "  ").single().name)
        assertEquals(groups, RecurringGroups.added(groups, "nope", listOf("x")))
    }

    @Test fun groupStateAndTotals() {
        val base = listOf(funds[0], funds[1], funds[2])
        val states = mapOf(funds[1].key to SubscriptionState.CANCELLED, funds[2].key to SubscriptionState.PAUSED)
        val g = RecurringGroups.arrange(base, listOf(sip.copy(memberKeys = base.map { it.key })), { it }, { states[it.key] ?: SubscriptionState.ACTIVE })
            .single() as RecurringListItem.Group
        assertEquals(1, g.activeCount); assertEquals(1, g.pausedCount); assertEquals(1, g.cancelledCount)
        assertEquals("1 active · 1 paused · 1 cancelled", g.stateSummary)
        assertEquals(BigDecimal("2500.00"), g.monthly) // only what counts
        val cancellable = RecurringGroups.cancellableKeys(g) { states[it.key] ?: SubscriptionState.ACTIVE }
        assertEquals(listOf(funds[0].key, funds[2].key), cancellable)
    }

    @Test fun cancelAllCoversMergedMembers() {
        val base = merged()
        val g = sip.copy(memberKeys = listOf(funds[0].key, funds[1].key, funds[2].key, icc[3].key))
        val group = RecurringGroups.apply(base, listOf(g)).filterIsInstance<RecurringListItem.Group<Subscription>>().single()
        val keys = RecurringGroups.cancellableKeys(group) { SubscriptionState.ACTIVE }
        assertEquals((funds.map { it.key } + icc.map { it.key }).toSet(), keys.toSet())
        assertEquals(keys.size, keys.distinct().size)
        assertEquals(keys.toSet(), RecurringGroups.allKeys(group).toSet())
    }

    @Test fun suggestionsForOneFundHouse() {
        val four = listOf("Manufacturing", "Multi Cap", "Value", "Small Cap").mapIndexed { i, n -> sub("Mahindra Manulife $n Fund", id = 30L + i) }
        val s = RecurringGroups.suggest(four + netflix).single()
        assertEquals("Mahindra Manulife Fund", s.name)
        assertEquals(4, s.keys.size)
        assertTrue(RecurringGroups.suggest(four, dismissed = setOf(s.id)).isEmpty())
        assertTrue(RecurringGroups.suggest(four, groups = listOf(sip.copy(memberKeys = four.map { it.key }))).isEmpty())
        assertTrue(RecurringGroups.suggest(four.take(1) + netflix).isEmpty())
    }

    @Test fun unrelatedPayeesAndBankNamesGetNoSuggestion() {
        val list = listOf(sub("Netflix", id = 1), sub("Spotify India", id = 2), sub("Tata Play", id = 3), sub("Tata Power", id = 4), sub("HDFC Life Insurance", id = 5), sub("HDFC Bank EMI", id = 6))
        assertTrue(RecurringGroups.suggest(list).isEmpty())
    }

    @Test fun fundWordMakesWeakNameCount() {
        val list = listOf(sub("HDFC Flexi Cap Fund", id = 1), sub("HDFC Mid Cap Fund Growth", id = 2))
        assertEquals("HDFC Fund", RecurringGroups.suggest(list).single().name)
    }

    @Test fun leadingPhraseGroupsSharedFirstTwoWords() {
        val list = listOf(sub("Zerodha Coin Large", id = 1), sub("Zerodha Coin Mid", id = 2), sub("Netflix", id = 3))
        assertEquals("Zerodha Coin", RecurringGroups.suggest(list).single().name)
        // Clearing-house names are not a provider.
        assertTrue(RecurringGroups.suggest(icc).isEmpty())
    }

    @Test fun suggestionsAreCappedAndDisjoint() {
        val list = listOf("Alpha Beta", "Gamma Delta", "Epsilon Zeta", "Eta Theta").flatMapIndexed { i, p -> listOf("Red", "Blue").mapIndexed { j, c -> sub("$p $c", id = i * 10L + j) } }
        val out = RecurringGroups.suggest(list)
        assertEquals(3, out.size)
        assertEquals(out.flatMap { it.keys }.size, out.flatMap { it.keys }.distinct().size)
    }

    @Test fun commonNameForSelection() {
        assertEquals("Mahindra Manulife Fund", RecurringGroups.commonName(funds + icc[3]))
        assertEquals("", RecurringGroups.commonName(listOf(netflix, icc[0])))
    }

    @Test fun namesReplaceSuffixAndWorkOnAnyEntry() {
        val base = merged()
        val named = RecurringNames.apply(base, mapOf(icc[3].key to "Small cap SIP", funds[0].key to "Manufacturing", "gone" to "x", funds[1].key to "  "))
        val renamed = named.first { it.key == icc[3].key }
        assertEquals("Small cap SIP", renamed.title)
        assertEquals("", renamed.suffix)
        assertEquals(icc[3].key, renamed.key)
        assertEquals("Manufacturing", named.first { it.key == funds[0].key }.title)
        assertNotEquals("", named.first { it.key == funds[1].key }.title)
        assertEquals(base.size, named.size)
        assertEquals(base, RecurringNames.apply(base, emptyMap()))
        assertEquals(mapOf("a" to "b"), RecurringNames.set(emptyMap(), "a", " b "))
        assertEquals(emptyMap<String, String>(), RecurringNames.set(mapOf("a" to "b"), "a", " "))
        assertEquals(emptyMap<String, String>(), RecurringNames.reset(mapOf("a" to "b", "c" to "d"), listOf("a", "c")))
        assertFalse(RecurringGroups.isGroupKey(icc[0].key))
    }

    @Test fun matchingNamesKeepOnlyWhatTheGroupNameDoesNotSay() {
        val names = RecurringGroups.matchingNames("Mahindra Manulife Fund", funds + icc[3])
        assertEquals("Mahindra Manulife Fund · Manufacturing", names[funds[0].key])
        assertEquals("Mahindra Manulife Fund · Multi", names[funds[1].key])
        assertEquals("Mahindra Manulife Fund · Value", names[funds[2].key])
        assertTrue(names[icc[3].key]!!.startsWith("Mahindra Manulife Fund · Indian Clearing Corp"))
        assertEquals(emptyMap<String, String>(), RecurringGroups.matchingNames("Mahindra Manulife", listOf(sub("Mahindra Manulife Value"))))
    }

    @Test fun normalizeDropsRepeatsAndEmpties() {
        val out = RecurringGroups.normalize(listOf(RecurringGroup("a", "A", listOf("k1", "k2")), RecurringGroup("b", "B", listOf("k2")), RecurringGroup("a", "A2", listOf("k9")), RecurringGroup("c", " ", listOf("k5"))))
        assertEquals(listOf("a"), out.map { it.id })
    }
}
