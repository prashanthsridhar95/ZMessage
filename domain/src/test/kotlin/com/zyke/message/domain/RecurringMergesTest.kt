package com.zyke.message.domain

import com.zyke.message.domain.insights.MergeSuggestions
import com.zyke.message.domain.insights.RecurringMerge
import com.zyke.message.domain.insights.RecurringMerges
import com.zyke.message.domain.insights.RecurringPayments
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.insights.SubscriptionOverview
import com.zyke.message.domain.insights.SubscriptionState
import com.zyke.message.domain.repository.RecurringState
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringMergesTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val due = LocalDate.of(2026, 11, 5)

    private fun sub(payee: String, ordinal: Int = 1, of: Int = 1, amount: String = "2500", times: Int = 3, next: LocalDate = due, last: LocalDate = next.minusMonths(1), id: Long = 1) =
        Subscription(
            payee, BigDecimal(amount), last, next, times, id, variant = if (of > 1) "v$ordinal" else "", ordinal = ordinal, ofCount = of
        )

    private val icc = (1..4).map { sub("Indian Clearing Corp", it, 4, times = 4, id = it.toLong()) }
    private val funds = listOf(
        sub("Mahindra Manulife Manufacturing Fund Reg", times = 6, id = 11),
        sub("Mahindra Manulife Multi Cap-Reg-Growth", times = 6, id = 12),
        sub("Mahindra Manulife Value Fund Reg Growth", times = 6, id = 13)
    )
    private val netflix = sub("Netflix", amount = "649", next = due.plusDays(9), id = 20)
    private val all = icc + funds + listOf(netflix)

    private fun pair(a: Subscription, b: Subscription, name: String? = null, at: Long = 1) = RecurringMerge(b.key, listOf(a.key), name, at)

    @Test fun pairwiseMergeCountsOnce() {
        val merges = listOf(pair(icc[0], funds[0]), pair(icc[1], funds[1]), pair(icc[2], funds[2]))
        val out = RecurringMerges.apply(all, merges)
        assertEquals(1 + 1 + 3, out.size) // netflix, icc #4, three merged
        val overview = SubscriptionOverview.build(out, emptyMap(), emptyMap(), today)
        // three merged SIPs and ICC #4 at 30,000 a year each, plus Netflix
        assertEquals(BigDecimal(30000 * 4 + 649 * 12), overview.yearlyTotal)
        val merged = out.first { it.key == funds[0].key }
        assertEquals(BigDecimal("2500"), merged.amount)
        assertEquals(BigDecimal(30000), merged.cadence.yearly(merged.amount))
        assertEquals(6, merged.times)
        assertEquals(listOf("Indian Clearing Corp #1 of 4"), merged.alsoSeenAs)
        assertEquals(2, merged.nameCount)
    }

    @Test fun oneGroupOfSeven() {
        val g = RecurringMerge(funds[0].key, (icc + funds.drop(1)).map { it.key }, "My SIPs", 1)
        val out = RecurringMerges.apply(all, listOf(g))
        assertEquals(2, out.size)
        val m = out.first { it.isMerged }
        assertEquals("My SIPs", m.title)
        assertEquals(6, m.alsoSeenAs.size)
        assertEquals(funds[0].key, m.key)
        assertEquals(BigDecimal(30000), m.cadence.yearly(m.amount))
    }

    @Test fun earliestDueLatestChargeMaxRun() {
        val a = sub("Bank Debit", next = due, last = LocalDate.of(2026, 10, 5), times = 3, id = 1)
        val b = sub("Fund House", next = due.plusDays(1), last = LocalDate.of(2026, 10, 6), times = 8, id = 2)
        val m = RecurringMerges.apply(listOf(a, b), listOf(RecurringMerge(b.key, listOf(a.key)))).single()
        assertEquals(due, m.nextDue)
        assertEquals(LocalDate.of(2026, 10, 6), m.lastPaid)
        assertEquals(8, m.times)
        assertEquals(2L, m.messageId)
    }

    @Test fun idempotentAndOrderIndependent() {
        val merges = listOf(pair(icc[0], funds[0]), pair(icc[1], funds[1]))
        val once = RecurringMerges.apply(all, merges)
        assertEquals(once, RecurringMerges.apply(once, merges))
        assertEquals(once, RecurringMerges.apply(all.reversed(), merges.reversed()))
        assertEquals(once, RecurringMerges.apply(all.shuffled(java.util.Random(3)), merges))
    }

    @Test fun stateComesFromPrimaryAndCancelPropagates() {
        val merges = listOf(pair(icc[0], funds[0]))
        val group = RecurringMerges.groupOf(funds[0].key, merges)
        assertEquals(setOf(icc[0].key, funds[0].key), group)
        val out = RecurringMerges.apply(all, merges)
        val cancelled = group.associateWith { LocalDate.of(2026, 11, 1) }
        val overview = SubscriptionOverview.build(out, emptyMap(), cancelled, today)
        assertEquals(SubscriptionState.CANCELLED, overview.lines.first { it.key == funds[0].key }.state)
        // a stale cancel on only a member is dropped, the primary's own wins
        val stale = RecurringMerges.harmonize(mapOf(icc[0].key to "x"), merges)
        assertTrue(stale.isEmpty())
        val copied = RecurringMerges.harmonize(mapOf(funds[0].key to "x"), merges)
        assertEquals(setOf(icc[0].key, funds[0].key), copied.keys)
        val items = RecurringPayments.arrange(out, mapOf(funds[0].key to RecurringState.IGNORED), emptySet())
        assertFalse(items.any { it.subscription.key == funds[0].key })
    }

    @Test fun unmergeRestores() {
        val merges = RecurringMerges.merged(emptyList(), listOf(pair(icc[0], funds[0])))
        assertEquals(all.size - 1, RecurringMerges.apply(all, merges).size)
        val none = RecurringMerges.unmerged(merges, icc[0].key)
        assertTrue(none.isEmpty())
        assertEquals(all.size, RecurringMerges.apply(all, none).size)
    }

    @Test fun chainsFlatten() {
        val ab = pair(icc[0], funds[0], at = 1)
        val bc = RecurringMerge(funds[1].key, listOf(funds[0].key), null, 2)
        val groups = RecurringMerges.normalize(listOf(ab, bc))
        assertEquals(1, groups.size)
        assertEquals(funds[1].key, groups[0].primaryKey)
        assertEquals(setOf(icc[0].key, funds[0].key, funds[1].key), groups[0].allKeys.toSet())
        assertEquals(all.size - 2, RecurringMerges.apply(all, listOf(ab, bc)).size)
    }

    @Test fun vanishedMembersAndPrimary() {
        val g = RecurringMerge("gone", listOf(icc[0].key, funds[0].key, "also gone"))
        val out = RecurringMerges.apply(all, listOf(g))
        assertEquals(all.size - 1, out.size)
        assertEquals(icc[0].key, out.first { it.isMerged }.key) // first remaining member is promoted
        val lone = RecurringMerges.apply(all, listOf(RecurringMerge(funds[0].key, listOf("gone"))))
        assertEquals(all.size, lone.size)
        assertFalse(lone.any { it.isMerged })
    }

    @Test fun keysStayStableWhenANewChargeAppears() {
        val merges = listOf(pair(icc[0], funds[0]))
        val before = RecurringMerges.apply(all, merges).first { it.isMerged }.key
        val moved = all.map { if (it.key == icc[0].key) it.copy(lastPaid = it.lastPaid.plusMonths(1), nextDue = it.nextDue.plusMonths(1), times = 5) else it }
        val after = RecurringMerges.apply(moved, merges).first { it.isMerged }
        assertEquals(before, after.key)
        assertEquals(due, after.nextDue) // earliest of the two
    }

    @Test fun differentAmountsKeepPrimaryAndWarn() {
        val a = sub("A", amount = "2500", id = 1)
        val b = sub("B", amount = "2600", id = 2)
        val preview = RecurringMerges.preview(b, listOf(a))
        assertTrue(preview.amountsDiffer)
        assertEquals(BigDecimal(31200), preview.yearly)
        assertEquals(BigDecimal(61200), preview.wouldBeYearly)
        assertEquals(BigDecimal("2600"), RecurringMerges.apply(listOf(a, b), listOf(RecurringMerges.build(b, listOf(a), null, 1))).single().amount)
    }

    @Test fun similarHintIgnoresMembers() {
        val hotstar = sub("Hotstar", amount = "299", id = 30)
        val netflix2 = sub("Netflix", amount = "649", next = due.plusDays(9), id = 31)
        val netflixIndia = sub("Netflix India", amount = "649", next = due.plusDays(9), id = 32)
        val out = RecurringMerges.apply(listOf(hotstar, netflix2, netflixIndia), listOf(RecurringMerge(netflix2.key, listOf(netflixIndia.key))))
        val overview = SubscriptionOverview.build(out, emptyMap(), emptyMap(), today)
        val hint = overview.lines.first { it.key == netflix2.key }.hints.single().text
        assertFalse(hint.contains("Netflix India"))
        assertTrue(hint.contains("Hotstar"))
    }

    @Test fun autoPairMatchesClosest() {
        val a1 = sub("Indian Clearing Corp", 1, 3, next = due, id = 1)
        val a2 = sub("Indian Clearing Corp", 2, 3, next = due.plusDays(10), id = 2)
        val a3 = sub("Indian Clearing Corp", 3, 3, amount = "1000", next = due, id = 3)
        val b1 = sub("Fund One", next = due.plusDays(10), id = 4)
        val b2 = sub("Fund Two", amount = "1000", next = due, id = 5)
        val b3 = sub("Fund Three", next = due, id = 6)
        val (l, r) = RecurringMerges.sides(listOf(a1, a2, a3, b1, b2, b3))
        assertEquals(3, l.size)
        val pairs = RecurringMerges.autoPair(l, r)
        assertEquals(3, pairs.size)
        assertEquals(b1.key, pairs.first { it.left.key == a2.key }.right.key)
        assertEquals(b2.key, pairs.first { it.left.key == a3.key }.right.key)
        assertEquals(b3.key, pairs.first { it.left.key == a1.key }.right.key)
        assertEquals(1, RecurringMerges.autoPair(l, r.take(1)).size) // leftovers stay unmerged
    }

    @Test fun suggestsOnlyConservatively() {
        val found = MergeSuggestions.find(all, emptySet())
        assertEquals(3, found.size) // at most three, each entry once
        assertEquals(6, found.flatMap { it.keys }.toSet().size)
        assertTrue(found.none { netflix.key in it.keys })
        val no = MergeSuggestions.find(all, found.map { it.id }.toSet())
        assertTrue(no.size <= 3)
        assertTrue(no.none { s -> s.id in found.map { it.id } })
        // two brands with the same amount and day but only 2 months: no hint; 3 months exactly: hint
        val x = sub("Alpha", times = 3, id = 40)
        val y = sub("Beta", times = 3, id = 41)
        assertEquals(1, MergeSuggestions.find(listOf(x, y), emptySet()).size)
        assertTrue(MergeSuggestions.find(listOf(x.copy(times = 2), y.copy(times = 2)), emptySet()).isEmpty())
        assertTrue(MergeSuggestions.find(listOf(x, y.copy(amount = BigDecimal("2800"))), emptySet()).isEmpty())
    }
}
