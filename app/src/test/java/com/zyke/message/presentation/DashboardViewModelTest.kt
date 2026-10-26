package com.zyke.message.presentation

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.dashboard.DashboardContract.Intent
import com.zyke.message.presentation.dashboard.DashboardViewModel
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val now = Instant.parse("2025-03-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val day = 86_400_000L

    private fun spend(id: Long, to: String, amount: String, daysAgo: Long) = SmsMessage(
        id, "AD-HDFCBK",
        "Sent Rs.$amount\nFrom HDFC Bank A/C x1234\nTo $to\nOn 12/01/25\nRef 123456",
        now.toEpochMilli() - daysAgo * day, true
    )

    private val inbox = listOf(
        spend(1, "friend@ybl", "500.00", 1),
        spend(2, "swiggy@ybl", "800.00", 2),
        spend(3, "friend@ybl", "300.00", 40)
    )

    private class FakeRepo(val source: Flow<List<SmsMessage>>) : SmsRepository {
        override fun observeInbox() = source
    }

    private class FakeBudgets : BudgetRepository {
        private val _budgets = MutableStateFlow<Map<MessageCategory, BigDecimal>>(emptyMap())
        override val budgets: StateFlow<Map<MessageCategory, BigDecimal>> = _budgets
        override fun setBudget(category: MessageCategory, amount: BigDecimal?) {
            _budgets.value = if (amount == null) _budgets.value - category else _budgets.value + (category to amount)
        }
        override fun clearAll() { _budgets.value = emptyMap() }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(source: Flow<List<SmsMessage>> = flow { emit(inbox) }) = DashboardViewModel(
        ObserveMessagesUseCase(FakeRepo(source), MessageClassifier(), Dispatchers.Unconfined), FakeBudgets(), clock
    )

    @Test
    fun withoutPermission_showsPrompt() {
        val vm = vm()
        vm.onIntent(Intent.ScreenStarted(false))
        assertTrue(vm.state.value.permissionMissing)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.dashboard)
    }

    @Test
    fun loadsAndBuildsDashboardForThisMonth() {
        val vm = vm()
        vm.onIntent(Intent.ScreenStarted(true))

        val dashboard = vm.state.value.dashboard!!
        assertEquals(1300.0, dashboard.total.toDouble(), 0.001)
        assertEquals(2, dashboard.vendors.size)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun periodDirectionAndCategory_rebuildTheDashboard() {
        val vm = vm()
        vm.onIntent(Intent.ScreenStarted(true))

        vm.onIntent(Intent.SelectPeriod(DashboardPeriod.ALL_TIME))
        assertEquals(1600.0, vm.state.value.dashboard!!.total.toDouble(), 0.001)

        vm.onIntent(Intent.SelectCategory(MessageCategory.FOOD))
        assertEquals(800.0, vm.state.value.dashboard!!.total.toDouble(), 0.001)

        vm.onIntent(Intent.SelectCategory(MessageCategory.FOOD)) // same chip again clears it
        assertNull(vm.state.value.category)

        vm.onIntent(Intent.SelectDirection(FlowDirection.RECEIVED))
        assertTrue(vm.state.value.dashboard!!.isEmpty)
    }

    @Test
    fun vendorExpansion_togglesAndResetsOnFilterChange() {
        val vm = vm()
        vm.onIntent(Intent.ScreenStarted(true))

        vm.onIntent(Intent.ToggleVendor("swiggy"))
        assertEquals("swiggy", vm.state.value.expandedVendor)
        vm.onIntent(Intent.ToggleVendor("swiggy"))
        assertNull(vm.state.value.expandedVendor)

        vm.onIntent(Intent.ToggleVendor("swiggy"))
        vm.onIntent(Intent.SelectPeriod(DashboardPeriod.LAST_MONTH))
        assertNull(vm.state.value.expandedVendor)
    }

    @Test
    fun failure_surfacesAsError() {
        val vm = vm(flow { throw SecurityException("denied") })
        vm.onIntent(Intent.ScreenStarted(true))
        assertTrue(vm.state.value.hasError)
    }

    @Test
    fun savingBudget_updatesDashboard_andBlankClearsIt() {
        val vm = vm()
        vm.onIntent(Intent.ScreenStarted(true))
        vm.onIntent(Intent.EditBudget(MessageCategory.FOOD))
        assertEquals(MessageCategory.FOOD, vm.state.value.editingBudget)

        vm.onIntent(Intent.SaveBudget(MessageCategory.FOOD, "1,000"))
        assertNull(vm.state.value.editingBudget)
        val food = vm.state.value.dashboard!!.categories.first { it.category == MessageCategory.FOOD }
        assertEquals(200.0, food.remaining!!.toDouble(), 0.001)

        vm.onIntent(Intent.SaveBudget(MessageCategory.FOOD, "  "))
        assertNull(vm.state.value.dashboard!!.categories.first { it.category == MessageCategory.FOOD }.budget)
    }

    @Test
    fun cardBills_areGroupedByCard_andIgnorePeriodAndFilters() {
        val bill = SmsMessage(
            10, "AD-HDFCBK",
            "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:20-03-2025 pay https://hdfcbk.io/x",
            now.toEpochMilli() - 2 * day, true
        )
        val vm = vm(flow { emit(inbox + bill) })
        vm.onIntent(Intent.ScreenStarted(true))
        vm.onIntent(Intent.SelectPeriod(DashboardPeriod.LAST_MONTH))
        vm.onIntent(Intent.SelectDirection(FlowDirection.RECEIVED))

        val card = vm.state.value.cardBills.single()
        assertEquals("HDFC Card •• 1234", card.label)
        assertEquals(12345.0, card.latest.amount.toDouble(), 0.001)

        vm.onIntent(Intent.ToggleCard(card.key))
        assertEquals(card.key, vm.state.value.expandedCard)
        vm.onIntent(Intent.ToggleCard(card.key))
        assertNull(vm.state.value.expandedCard)
    }

    @Test
    fun retry_afterAFailure_loadsAgain() {
        var attempts = 0
        val vm = vm(flow { if (attempts++ == 0) throw SecurityException("boom") else emit(inbox) })
        vm.onIntent(Intent.ScreenStarted(true))
        assertTrue(vm.state.value.hasError)

        vm.onIntent(Intent.Retry)

        assertFalse(vm.state.value.hasError)
        assertTrue(vm.state.value.dashboard != null)
    }
}
