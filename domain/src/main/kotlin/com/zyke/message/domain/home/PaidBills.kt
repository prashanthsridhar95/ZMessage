package com.zyke.message.domain.home

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A bill the person said they paid outside the messages; see [UpcomingItem.markedPaidKey]. */
data class PaidBill(
    /** What "Mark as paid" stored for it; restoring the bill restores this key. */
    val key: String,
    /** The key the same bill has in the attention strip, if it has one. */
    val attentionKey: String?,
    val title: String,
    val amount: BigDecimal,
    val dueDate: LocalDate,
    val markedOn: LocalDate
) {
    companion object {
        fun from(item: UpcomingItem, today: LocalDate) =
            PaidBill(item.markedPaidKey, item.attentionKey, item.title, item.amount, item.date, today)
    }
}

/** The bills the person marked as paid, newest first, so one marked by mistake can be brought back. */
interface PaidBillRepository {
    val bills: StateFlow<List<PaidBill>>
    /** Records [bill]; marking the same bill again replaces the earlier record. */
    fun record(bill: PaidBill)
    fun remove(key: String)
}

object NoPaidBills : PaidBillRepository {
    override val bills: StateFlow<List<PaidBill>> = MutableStateFlow(emptyList())
    override fun record(bill: PaidBill) = Unit
    override fun remove(key: String) = Unit
}

object PaidBills {
    const val MAX_KEPT = 100

    /** [bill] added to the front of [existing], replacing any record with its key, keeping the newest [MAX_KEPT]. */
    fun add(existing: List<PaidBill>, bill: PaidBill): List<PaidBill> =
        (listOf(bill) + existing.filter { it.key != bill.key }).take(MAX_KEPT)
}
