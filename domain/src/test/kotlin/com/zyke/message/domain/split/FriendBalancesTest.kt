package com.zyke.message.domain.split

import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendBalancesTest {
    private val zone = ZoneId.of("UTC")

    private fun msg(id: Long, day: Int, amount: String, received: Boolean = false, payee: String = "Cafe") = ClassifiedMessage(
        message = SmsMessage(id, "AD-HDFCBK", "x", LocalDate.of(2026, 10, day).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true),
        category = MessageCategory.FOOD,
        merchant = Merchant(payee, MerchantLogo.UPI, MessageCategory.FOOD),
        content = MessageContent.Transaction(BankName.HDFC, payee, amount, "1234", received, false, false)
    )

    private fun split(id: String, message: Long, paidBy: SplitPayer = SplitPayer.ME, vararg people: SplitPerson) =
        Split(id, message, "Dinner", BigDecimal(1000), people.toList(), paidBy)

    private fun p(name: String, share: Int, settled: Boolean = false) = SplitPerson(name, BigDecimal(share), settled)

    @Test
    fun addsUpAcrossPaymentsForOneFriend() {
        val splits = listOf(
            split("a", 1, SplitPayer.ME, p("Ravi", 500), p("Priya", 300)),
            split("b", 2, SplitPayer.ME, p("ravi", 850))
        )
        val result = FriendBalances.build(splits, listOf(msg(1, 3, "1000"), msg(2, 8, "2000")), zone)
        val ravi = result.first { it.name == "Ravi" }
        assertEquals(BigDecimal(1350), ravi.net)
        assertEquals(2, ravi.open.size)
        assertEquals(LocalDate.of(2026, 10, 8), ravi.lastActivity)
        assertEquals("Ravi owes you ₹1,350 across 2 payments", ravi.suggestion)
        assertEquals("Ravi", result.first().name) // biggest balance first
    }

    @Test
    fun mixedDirectionsNet() {
        val splits = listOf(
            split("a", 1, SplitPayer.ME, p("Ravi", 500)),
            split("b", 2, SplitPayer.FRIEND, p("Ravi", 800))
        )
        val ravi = FriendBalances.build(splits, emptyList(), zone).single()
        assertEquals(BigDecimal(500), ravi.owedToYou)
        assertEquals(BigDecimal(800), ravi.youOwe)
        assertEquals(BigDecimal(-300), ravi.net)
        assertEquals("You owe Ravi ₹300 across 2 payments", ravi.suggestion)
    }

    @Test
    fun settledItemsAreExcluded() {
        val splits = listOf(split("a", 1, SplitPayer.ME, p("Ravi", 500, settled = true)), split("b", 2, SplitPayer.ME, p("Ravi", 200)))
        val ravi = FriendBalances.build(splits, emptyList(), zone).single()
        assertEquals(BigDecimal(200), ravi.net)
        assertEquals(1, ravi.settledCount)
        assertEquals("Ravi owes you ₹200 across 1 payment", ravi.suggestion)
    }

    @Test
    fun autoSettledByCreditIsExcludedAndFriendPaidIsNeverAutoSettled() {
        val messages = listOf(msg(1, 3, "1000"), msg(3, 5, "500", received = true, payee = "Ravi K"), msg(2, 4, "1000"))
        val splits = listOf(
            split("a", 1, SplitPayer.ME, p("Ravi", 500)),
            split("b", 2, SplitPayer.FRIEND, p("Ravi", 500))
        )
        val ravi = FriendBalances.build(splits, messages, zone).single()
        assertEquals(BigDecimal.ZERO, ravi.owedToYou)
        assertEquals(BigDecimal(500), ravi.youOwe)
    }

    @Test
    fun allSettledSortsLastAndSettleAllMarksOnlyThatFriend() {
        val splits = listOf(
            split("a", 1, SplitPayer.ME, p("Ravi", 500), p("Priya", 300)),
            split("b", 2, SplitPayer.FRIEND, p("Ravi", 100))
        )
        val shares = SplitInsights.build(splits, emptyList(), zone)
        val changed = FriendBalances.settleAll(splits, shares, "Ravi")
        assertEquals(2, changed.size)
        assertTrue(changed.all { s -> s.people.filter { it.name == "Ravi" }.all { it.settledByHand } })
        assertTrue(!changed.first { it.id == "a" }.people.first { it.name == "Priya" }.settledByHand)
        val after = FriendBalances.build(splits.map { s -> changed.firstOrNull { it.id == s.id } ?: s }, emptyList(), zone)
        assertEquals(listOf("Priya", "Ravi"), after.map { it.name })
        assertTrue(after.last().isSettled)
        assertEquals("All settled with Ravi", after.last().suggestion)
    }

    @Test
    fun payerDefaultsToMeAndParsesOldValues() {
        assertEquals(SplitPayer.ME, Split("x", 1, "t", BigDecimal.TEN, emptyList()).paidBy)
        assertEquals(SplitPayer.ME, SplitPayer.parse(null))
        assertEquals(SplitPayer.ME, SplitPayer.parse("garbage"))
        assertEquals(SplitPayer.FRIEND, SplitPayer.parse("FRIEND"))
    }

    @Test
    fun outstandingCountsOnlyWhatIsOwedToYou() {
        val splits = listOf(split("a", 1, SplitPayer.ME, p("Ravi", 500)), split("b", 2, SplitPayer.FRIEND, p("Ravi", 100)))
        assertEquals(BigDecimal(500), SplitInsights.outstanding(SplitInsights.build(splits, emptyList(), zone)))
    }
}
