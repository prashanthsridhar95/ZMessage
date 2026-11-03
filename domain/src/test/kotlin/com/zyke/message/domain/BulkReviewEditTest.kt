package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.CustomCategories
import com.zyke.message.domain.categories.CustomCategoryState
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.ReviewSelection
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CustomCategory
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.nickname.BulkEditText
import com.zyke.message.domain.nickname.CategoryChoice
import com.zyke.message.domain.nickname.NamingEdits
import com.zyke.message.domain.nickname.NamingEdits.Target
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.CustomCategoryStore
import com.zyke.message.domain.repository.NicknameRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkReviewEditTest {
    private class Names : NicknameRepository {
        var writes = 0
        val flow = MutableStateFlow<Map<String, String>>(emptyMap())
        override val nicknames: StateFlow<Map<String, String>> = flow
        override fun set(key: String, name: String?) { writes++; flow.value = if (name.isNullOrBlank()) flow.value - key else flow.value + (key to name) }
        override fun setAll(changes: Map<String, String?>) {
            writes++
            flow.value = changes.entries.fold(flow.value) { m, (k, n) -> if (n.isNullOrBlank()) m - k else m + (k to n) }
        }
    }

    private class Overrides : CategoryOverrideRepository {
        var writes = 0
        val flow = MutableStateFlow<Map<String, MessageCategory>>(emptyMap())
        override val overrides: StateFlow<Map<String, MessageCategory>> = flow
        override fun set(key: String, category: MessageCategory?) { writes++; flow.value = if (category == null) flow.value - key else flow.value + (key to category) }
        override fun setAll(changes: Map<String, MessageCategory?>) {
            writes++
            flow.value = changes.entries.fold(flow.value) { m, (k, c) -> if (c == null) m - k else m + (k to c) }
        }
        override fun clearAll() { flow.value = emptyMap() }
    }

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-25T09:00:00Z")
    private val names = Names()
    private val overrides = Overrides()
    private val trip = CustomCategory("c9", "Trips")
    private val customs = CustomCategoryStore(CustomCategoryState(listOf(trip), emptyMap()))
    private val edits = NamingEdits(names, overrides, customs)
    private var clock = now.toEpochMilli() - 3_600_000

    private fun big(id: Long, payee: String) = ClassifiedMessage(
        message = SmsMessage(id, "AD-HDFCBK", "Rs 5000 debited to $payee", clock++, true),
        category = MessageCategory.OTHERS,
        merchant = Merchant(payee, MerchantLogo.UPI, MessageCategory.OTHERS),
        content = MessageContent.Transaction(BankName.HDFC, payee, "5000.00", "5765", false, false)
    )

    private fun inbox(vararg raw: ClassifiedMessage): List<ClassifiedMessage> {
        val applied = raw.map { CategoryKey.apply(it, overrides.flow.value) }.map { m ->
            names.flow.value[CategoryKey.messageKey(m.id)]?.let { n -> m.copy(merchant = m.merchant.copy(name = n, renamed = true)) }
                ?: names.flow.value[CategoryKey.of(m)]?.let { n -> m.copy(merchant = m.merchant.copy(name = n, renamed = true)) } ?: m
        }
        return CustomCategories.apply(applied, customs.state.value)
    }

    private fun uncategorised(messages: List<ClassifiedMessage>) =
        ReviewQueues.build(messages, SetAsides(), zone, now).uncategorised.map { it.id }

    @Test fun anUnclearPaymentLeavesTheListOnceItIsFiledNamedOrGivenACustomCategory() {
        val raw = arrayOf(big(1, "Alpha"), big(2, "Beta"), big(3, "Gamma"), big(4, "Delta"))
        assertEquals(setOf(1L, 2L, 3L, 4L), uncategorised(inbox(*raw)).toSet())
        overrides.set(CategoryKey.messageKey(1), MessageCategory.SHOPPING)
        names.set(CategoryKey.messageKey(2), "My beta")
        customs.assign(CategoryKey.of(raw[2])!!, trip.id)
        assertEquals(listOf(4L), uncategorised(inbox(*raw)))
    }

    @Test fun onlySingleTransactionsCanBePicked() {
        val messages = inbox(big(1, "Alpha"), big(2, "Beta"))
        val review = ReviewQueues.build(messages, SetAsides(), zone, now)
        assertEquals(setOf(1L, 2L), ReviewSelection.selectable(review).map { it.id }.toSet())
    }

    @Test fun pickingAndPruning() {
        var picked = emptyList<Long>()
        picked = ReviewSelection.toggle(picked, 1)
        picked = ReviewSelection.toggle(picked, 2)
        picked = ReviewSelection.toggle(picked, 1)
        assertEquals(listOf(2L), picked)
        picked = ReviewSelection.selectAll(picked, listOf(2, 3, 4))
        assertEquals(listOf(2L, 3L, 4L), picked)
        assertTrue(ReviewSelection.allPicked(picked, listOf(3, 4)))
        assertEquals(listOf(3L), ReviewSelection.prune(picked, listOf(3, 9)))
        assertEquals("Selection mode, 3 selected", ReviewSelection.announcement(3))
    }

    @Test fun aBulkRenameAndFilingIsOneWriteAndOneUndo() {
        val messages = inbox(big(1, "A"), big(2, "B"), big(3, "C"))
        val targets = (1L..3L).map { Target(CategoryKey.messageKey(it), setOf(it)) }
        val out = edits.apply(NamingEdits.Request(targets, "Bajaj SIP", CategoryChoice.of(MessageCategory.INVESTMENTS)), messages, emptySet())
        assertEquals(1, names.writes)
        assertEquals(1, overrides.writes)
        assertEquals(3, out.renamed)
        assertEquals(3, out.refiled)
        assertEquals("Bajaj SIP", names.flow.value[CategoryKey.messageKey(2)])
        edits.undo(out.receipt)
        assertTrue(names.flow.value.isEmpty())
        assertTrue(overrides.flow.value.isEmpty())
    }

    @Test fun payeeScopeNamesThePayeeSoFuturePaymentsFollow() {
        val a1 = big(1, "Bajaj"); val a2 = big(2, "Bajaj"); val other = big(3, "Other")
        val messages = inbox(a1, a2, other)
        val payee = CategoryKey.of(a1)!!
        val out = edits.apply(NamingEdits.Request(listOf(Target(payee, setOf(1, 2))), "Bajaj SIP", CategoryChoice.custom(trip.id)), messages, emptySet())
        assertEquals("Bajaj SIP", names.flow.value[payee])
        assertEquals(trip.id, customs.state.value.assignments[payee])
        assertNull(names.flow.value[CategoryKey.of(other)!!])
        // A later payment to the same payee shows the name and the category.
        val later = inbox(a1, a2, other, big(4, "Bajaj"))
        assertEquals(trip.id, later.first { it.id == 4L }.customCategoryId)
        assertEquals("Bajaj SIP", later.first { it.id == 4L }.merchant.name)
        edits.undo(out.receipt)
        assertTrue(customs.state.value.assignments.isEmpty())
    }

    @Test fun aBulkRenameToAnExistingNameAdoptsItsCategoryUnlessOneIsChosen() {
        val known = big(10, "Fund house")
        names.set(CategoryKey.of(known)!!, "Bajaj SIP")
        overrides.set(CategoryKey.of(known)!!, MessageCategory.INVESTMENTS)
        val messages = inbox(known, big(1, "A"), big(2, "B"))
        val targets = listOf(1L, 2L).map { Target(CategoryKey.messageKey(it), setOf(it)) }
        val adopted = edits.apply(NamingEdits.Request(targets, "bajaj sip"), messages, emptySet())
        assertEquals(CategoryChoice.of(MessageCategory.INVESTMENTS), adopted.inherited)
        assertEquals(MessageCategory.INVESTMENTS, overrides.flow.value[CategoryKey.messageKey(1)])
        edits.undo(adopted.receipt)
        val chosen = edits.apply(NamingEdits.Request(targets, "bajaj sip", CategoryChoice.of(MessageCategory.SAVINGS)), messages, emptySet())
        assertNull(chosen.inherited)
        assertEquals(MessageCategory.SAVINGS, overrides.flow.value[CategoryKey.messageKey(2)])
    }

    @Test fun categoryOnlyEditLeavesNamesAlone() {
        val messages = inbox(big(1, "A"))
        names.set(CategoryKey.messageKey(1), "Keep me")
        edits.apply(NamingEdits.Request(listOf(Target(CategoryKey.messageKey(1), setOf(1))), category = CategoryChoice.of(MessageCategory.TRAVEL)), messages, emptySet())
        assertEquals("Keep me", names.flow.value[CategoryKey.messageKey(1)])
        assertEquals(MessageCategory.TRAVEL, overrides.flow.value[CategoryKey.messageKey(1)])
    }

    @Test fun thePreviewSaysWhatWillHappen() {
        assertEquals("Will rename 5 payments to \"Bajaj SIP\" and file them under Investments", BulkEditText.preview(5, "Bajaj SIP", "Investments"))
        assertEquals("Will rename 1 payment to \"X\" and file it under Y like your other \"X\" payments", BulkEditText.preview(1, "X", "Y", fromName = true))
        assertEquals("Will file 2 payments under Trips", BulkEditText.preview(2, "", "Trips"))
        assertEquals("Will rename 3 payments to \"Z\"", BulkEditText.preview(3, " Z ", ""))
        assertEquals("Updated 5 payments", BulkEditText.done(5))
        assertFalse(BulkEditText.preview(0, "Z", "Y").startsWith("Will"))
    }
}
