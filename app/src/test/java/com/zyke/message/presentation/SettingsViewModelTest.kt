package com.zyke.message.presentation

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.repository.BudgetRepository
import com.zyke.message.domain.repository.SettingsRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.settings.SettingsContract.Effect
import com.zyke.message.presentation.settings.SettingsContract.Intent
import com.zyke.message.presentation.settings.SettingsViewModel
import java.math.BigDecimal
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private class FakeSms(val source: Flow<List<SmsMessage>>) : SmsRepository {
        override fun observeInbox() = source
    }

    private class FakeSettings : SettingsRepository {
        private val _s = MutableStateFlow(AppSettings())
        override val settings: StateFlow<AppSettings> = _s
        override fun setScreenProtection(enabled: Boolean) { _s.value = _s.value.copy(screenProtection = enabled) }
        override fun setThemeMode(mode: com.zyke.message.domain.model.ThemeMode) { _s.value = _s.value.copy(themeMode = mode) }
        override fun setFetchLogos(enabled: Boolean) { _s.value = _s.value.copy(fetchLogos = enabled) }
        override fun setLiveAlerts(enabled: Boolean) { _s.value = _s.value.copy(liveAlerts = enabled) }
        override fun setAppLock(enabled: Boolean) { _s.value = _s.value.copy(appLock = enabled) }
        override fun setMorningDigest(enabled: Boolean) { _s.value = _s.value.copy(morningDigest = enabled) }
    }

    private class FakeUser : UserMessageStateRepository {
        var cleared = false
        override val state: StateFlow<UserMessageState> = MutableStateFlow(UserMessageState())
        override fun markRead(messageId: Long) {}
        override fun setArchived(messageId: Long, archived: Boolean) {}
        override fun setPinned(messageId: Long, pinned: Boolean) {}
        override fun dismissAttention(key: String, forever: Boolean) {}
        override fun clearAll() { cleared = true }
    }

    private class FakeBudgets : BudgetRepository {
        var cleared = false
        override val budgets: StateFlow<Map<MessageCategory, BigDecimal>> = MutableStateFlow(emptyMap())
        override fun setBudget(category: MessageCategory, amount: BigDecimal?) {}
        override fun clearAll() { cleared = true }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val user = FakeUser()
    private val budgets = FakeBudgets()
    private val inbox = listOf(
        SmsMessage(1, "AD-BANK", "Fee of Rs 15 applies to your account 1234567890", 0, true),
        SmsMessage(2, "AD-BANK", "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1", 0, true)
    )

    private fun vm() = SettingsViewModel(
        ObserveMessagesUseCase(FakeSms(flow { emit(inbox) }), MessageClassifier(), Dispatchers.Unconfined),
        FakeSettings(), user, budgets
    )

    @Test
    fun screenProtection_isToggled() {
        val vm = vm()
        vm.onIntent(Intent.SetScreenProtection(true))
        assertTrue(vm.state.value.settings.screenProtection)
    }

    @Test
    fun reset_needsConfirmation_andClearsBothStores() {
        val vm = vm()
        vm.onIntent(Intent.AskReset)
        assertTrue(vm.state.value.confirmingReset)
        assertFalse(user.cleared)

        vm.onIntent(Intent.CancelReset)
        assertFalse(vm.state.value.confirmingReset)
        assertFalse(user.cleared)

        vm.onIntent(Intent.AskReset)
        vm.onIntent(Intent.ConfirmReset)
        assertTrue(user.cleared && budgets.cleared)
        assertFalse(vm.state.value.confirmingReset)
    }

    @Test
    fun diagnostics_reportRedactedSamples() = runTest {
        val vm = vm()
        vm.onIntent(Intent.ScreenStarted(true))
        val diagnostics = vm.state.value.diagnostics
        assertNotNull(diagnostics)
        assertEquals(2, diagnostics!!.moneyMessages)
        assertEquals(1, diagnostics.unrecognised)

        vm.onIntent(Intent.ShareDiagnostics)
        val effect = vm.effects.first() as Effect.ShareReport
        assertTrue(Regex("zyke-parsing-report-\\d{8}-\\d{4}\\.txt").matches(effect.fileName))
        assertTrue("Fee of Rs 99 applies" in effect.text)
        assertTrue(effect.text.substringAfter("--- 1 ---").none { it in "012345678" })
    }

    @Test
    fun copyingWithoutPermission_doesNothing() = runTest {
        val vm = vm()
        vm.onIntent(Intent.ShareDiagnostics)
        assertEquals(null, vm.state.value.diagnostics)
    }
}
