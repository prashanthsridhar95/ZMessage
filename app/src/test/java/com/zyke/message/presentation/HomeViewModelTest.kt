package com.zyke.message.presentation

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.domain.home.UpcomingKind
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.home.HomeContract.Effect
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeViewModel
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val day = 86_400_000L

    private fun spend(id: Long, to: String, amount: String, daysAgo: Long) = SmsMessage(
        id, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank A/C x1234\nTo $to\nOn 12/01/25\nRef 1",
        now.toEpochMilli() - daysAgo * day, true
    )

    private val inbox = listOf(
        spend(1, "swiggy@ybl", "800.00", 1),
        spend(2, "friend@ybl", "200.00", 2),
        SmsMessage(3, "AD-SWIGGY", "482913 is your OTP for Swiggy", now.toEpochMilli() - 60_000, false),
        SmsMessage(
            4, "AD-HDFCBK",
            "Alert:\nINR.650.00 will be debited on 28/10/2026 from HDFC Bank Card 0421 for Google Play - Conten..",
            now.toEpochMilli() - day, true
        )
    )

    private class FakeSms(val source: Flow<List<SmsMessage>>) : SmsRepository {
        override fun observeInbox() = source
    }

    private class FakeUserState : UserMessageStateRepository {
        private val _state = MutableStateFlow(UserMessageState())
        override val state: StateFlow<UserMessageState> = _state
        override fun markRead(messageId: Long) { _state.value = _state.value.copy(readIds = _state.value.readIds + messageId) }
        override fun setArchived(messageId: Long, archived: Boolean) {}
        override fun setPinned(messageId: Long, pinned: Boolean) {}
        override fun clearAll() { _state.value = UserMessageState() }
        override fun dismissAttention(key: String) {
            _state.value = _state.value.copy(dismissedAttention = _state.value.dismissedAttention + key)
        }
    }

    private class FakeBudgets(initial: Map<MessageCategory, BigDecimal> = emptyMap()) : BudgetRepository {
        private val _b = MutableStateFlow(initial)
        override val budgets: StateFlow<Map<MessageCategory, BigDecimal>> = _b
        override fun setBudget(category: MessageCategory, amount: BigDecimal?) {}
        override fun clearAll() {}
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val userState = FakeUserState()

    private fun vm(source: Flow<List<SmsMessage>> = flow { emit(inbox) }, budgets: Map<MessageCategory, BigDecimal> = emptyMap()) =
        HomeViewModel(
            ObserveMessagesUseCase(FakeSms(source), MessageClassifier(), Dispatchers.Unconfined),
            userState, FakeBudgets(budgets), clock
        )

    @Test
    fun withoutPermission_asksForIt() {
        val vm = vm()
        vm.onIntent(Intent.ScreenStarted(false))
        assertTrue(vm.state.value.permissionMissing)
        assertNull(vm.state.value.thisMonth)
    }

    @Test
    fun buildsOverview() {
        val vm = vm(budgets = mapOf(MessageCategory.FOOD to BigDecimal("1000")))
        vm.onIntent(Intent.ScreenStarted(true))
        val state = vm.state.value

        assertEquals(1000.0, state.thisMonth!!.total.toDouble(), 0.001)
        assertEquals(listOf(UpcomingKind.AUTO_DEBIT), state.upcoming.map { it.kind })
        assertTrue(state.attention.any { it is AttentionItem.FreshOtp })
        assertEquals(listOf(1L, 2L), state.recent.map { it.id })
        assertEquals(MessageCategory.FOOD, state.budgetStatus.single().category)
        assertEquals(1, state.unreadCount)
    }

    @Test
    fun dismissingAttention_removesIt() {
        val vm = vm()
        vm.onIntent(Intent.ScreenStarted(true))
        val key = vm.state.value.attention.first().key

        vm.onIntent(Intent.DismissAttention(key))

        assertTrue(vm.state.value.attention.none { it.key == key })
    }

    @Test
    fun copyOtp_emitsEffectAndMarksRead() = runTest {
        val vm = vm()
        vm.onIntent(Intent.CopyOtp(3, "482913"))
        assertEquals(Effect.CopyToClipboard("One-time Password", "482913"), vm.effects.first())
        assertTrue(3L in userState.state.value.readIds)
    }

    @Test
    fun retry_afterAFailure_loadsAgain() {
        var attempts = 0
        val vm = vm(flow { if (attempts++ == 0) throw SecurityException("boom") else emit(inbox) })
        vm.onIntent(Intent.ScreenStarted(true))
        assertTrue(vm.state.value.hasError)

        vm.onIntent(Intent.Retry)

        assertTrue(!vm.state.value.hasError && vm.state.value.thisMonth != null)
    }
}
