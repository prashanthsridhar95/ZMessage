package com.zyke.message.domain

import com.zyke.message.domain.home.PaidBill
import com.zyke.message.domain.home.PaidBills
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class PaidBillsTest {
    private fun bill(key: String, amount: String = "100") =
        PaidBill(key, null, key, BigDecimal(amount), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6))

    @Test fun newestFirstAndRemarkingReplaces() {
        val list = PaidBills.add(PaidBills.add(emptyList(), bill("a")), bill("b"))
        assertEquals(listOf("b", "a"), list.map { it.key })
        val again = PaidBills.add(list, bill("a", "250"))
        assertEquals(listOf("a", "b"), again.map { it.key })
        assertEquals(BigDecimal("250"), again.first().amount)
    }

    @Test fun keepsOnlyTheNewest() {
        val list = (1..PaidBills.MAX_KEPT + 5).fold(emptyList<PaidBill>()) { acc, i -> PaidBills.add(acc, bill("k$i")) }
        assertEquals(PaidBills.MAX_KEPT, list.size)
        assertEquals("k${PaidBills.MAX_KEPT + 5}", list.first().key)
    }
}
