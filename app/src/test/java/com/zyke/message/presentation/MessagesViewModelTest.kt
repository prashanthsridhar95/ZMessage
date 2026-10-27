package com.zyke.message.presentation

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.feed.StatusFilter
import com.zyke.message.domain.feed.TimeWindow
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.messages.MessagesContract.Effect
import com.zyke.message.presentation.messages.MessagesContract.Intent
import com.zyke.message.presentation.messages.MessagesContract.PermissionStatus
import com.zyke.message.presentation.messages.MessagesContract.ViewMode
import com.zyke.message.presentation.messages.MessagesViewModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {

    private class FakeSmsRepository(val inbox: Flow<List<SmsMessage>>) : SmsRepository {
        override fun observeInbox() = inbox
    }

    private class FakeUserState : UserMessageStateRepository {
        private val _state = MutableStateFlow(UserMessageState())
        override val state: StateFlow<UserMessageState> = _state
        override fun markRead(messageId: Long) { _state.value = _state.value.copy(readIds = _state.value.readIds + messageId) }
        override fun setArchived(messageId: Long, archived: Boolean) {
            val ids = _state.value.archivedIds
            _state.value = _state.value.copy(archivedIds = if (archived) ids + messageId else ids - messageId)
        }
        override fun setPinned(messageId: Long, pinned: Boolean) {
            val ids = _state.value.pinnedIds
            _state.value = _state.value.copy(pinnedIds = if (pinned) ids + messageId else ids - messageId)
        }
        override fun clearAll() { _state.value = UserMessageState() }
        override fun dismissAttention(key: String) {
            _state.value = _state.value.copy(dismissedAttention = _state.value.dismissedAttention + key)
        }
    }

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.of("UTC"))
    private val inbox = listOf(
        SmsMessage(1, "SWIGGY", "Your Swiggy order is on the way", now - 60_000, true),
        SmsMessage(2, "AD-BANK", "123456 is your OTP", now - 3 * day, false)
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val userState = FakeUserState()

    private fun viewModel(source: Flow<List<SmsMessage>>) = MessagesViewModel(
        ObserveMessagesUseCase(FakeSmsRepository(source), MessageClassifier(), Dispatchers.Unconfined),
        userState,
        clock
    )

    private fun MessagesViewModel.visibleIds() =
        state.value.feed.sections.flatMap { it.items }.flatMap { listOf(it.newest.id) }

    @Test
    fun withoutPermission_requestsItAndLoadsNothing() = runTest {
        val vm = viewModel(MutableSharedFlow())

        vm.onIntent(Intent.ScreenStarted(permissionGranted = false))

        assertEquals(Effect.LaunchPermissionRequest, vm.effects.first())
        assertEquals(PermissionStatus.UNKNOWN, vm.state.value.permission)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun denial_isReflectedInState() {
        val vm = viewModel(MutableSharedFlow())
        vm.onIntent(Intent.PermissionResult(false))
        assertEquals(PermissionStatus.DENIED, vm.state.value.permission)
    }

    @Test
    fun grantedPermission_loadsAndBuildsFeed() {
        val vm = viewModel(flow { emit(inbox) })

        vm.onIntent(Intent.ScreenStarted(permissionGranted = true))

        val state = vm.state.value
        assertEquals(PermissionStatus.GRANTED, state.permission)
        assertFalse(state.isLoading)
        assertEquals(2, state.feed.visibleCount)
        assertEquals(2, state.feed.sections.size)
        assertEquals(listOf(1L, 2L), vm.visibleIds())
    }

    @Test
    fun filterSheet_combinesTypeAndCategory_andResets() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))

        vm.onIntent(Intent.OpenFilters)
        assertTrue(vm.state.value.showFilterSheet)

        vm.onIntent(Intent.SelectSmartFilter(SmartFilter.OTP))
        assertEquals(listOf(2L), vm.visibleIds())

        vm.onIntent(Intent.SelectCategory(MessageCategory.FOOD))
        assertEquals(0, vm.state.value.feed.visibleCount) // OTP + Food: nothing

        vm.onIntent(Intent.SelectSmartFilter(SmartFilter.ALL))
        assertEquals(listOf(1L), vm.visibleIds())          // Food only
        assertEquals(1, vm.state.value.filter.activeCount)

        vm.onIntent(Intent.ResetFilters)
        assertEquals(MessageFilter.All, vm.state.value.filter)
        assertEquals(2, vm.state.value.feed.visibleCount)

        vm.onIntent(Intent.CloseFilters)
        assertFalse(vm.state.value.showFilterSheet)
    }

    @Test
    fun search_filtersAndClearsWhenClosed() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))

        vm.onIntent(Intent.ToggleSearch)
        vm.onIntent(Intent.SetQuery("swiggy"))
        assertEquals(listOf(1L), vm.visibleIds())

        vm.onIntent(Intent.ToggleSearch)
        assertEquals("", vm.state.value.query)
        assertEquals(2, vm.state.value.feed.visibleCount)
    }

    @Test
    fun openingAMessage_showsItsDetail_marksItRead_andCanBeClosed() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))
        val unread = vm.state.value.messages.first { it.id == 2L }
        assertTrue(vm.state.value.isUnread(unread))

        vm.onIntent(Intent.OpenMessage(2))

        assertEquals(2L, vm.state.value.detail?.id)
        assertFalse(vm.state.value.isUnread(unread))
        assertNull(vm.state.value.thread) // no sender conversation is opened

        vm.onIntent(Intent.CloseDetail)
        assertNull(vm.state.value.detail)
    }

    @Test
    fun openingAnUnknownMessage_doesNothing() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))
        vm.onIntent(Intent.OpenMessage(999))
        assertNull(vm.state.value.detail)
    }

    @Test
    fun archive_hidesMessage_offersUndo_andRestoring() = runTest {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))

        vm.onIntent(Intent.Archive(1))
        assertEquals(Effect.MessageArchived(1), vm.effects.first())
        assertEquals(listOf(2L), vm.visibleIds())
        assertTrue(SmartFilter.ARCHIVED in vm.state.value.feed.availableFilters)

        vm.onIntent(Intent.SelectSmartFilter(SmartFilter.ARCHIVED))
        assertEquals(listOf(1L), vm.visibleIds())

        vm.onIntent(Intent.Unarchive(1))
        assertEquals(MessageFilter.All, vm.state.value.filter)
        assertEquals(listOf(1L, 2L), vm.visibleIds())
    }

    @Test
    fun pin_movesMessageToPinnedSection() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))

        vm.onIntent(Intent.TogglePin(2))
        assertEquals(2L, vm.state.value.feed.sections.first().items.single().newest.id)

        vm.onIntent(Intent.TogglePin(2))
        assertEquals(1L, vm.state.value.feed.sections.first().items.single().newest.id)
    }

    @Test
    fun copyOtp_emitsClipboardEffectAndMarksRead() = runTest {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.CopyOtp(2, "123456"))
        assertEquals(Effect.CopyToClipboard("One-time Password", "123456"), vm.effects.first())
        assertTrue(2L in userState.state.value.readIds)
    }

    @Test
    fun repositoryFailure_surfacesAsError() {
        val vm = viewModel(flow { throw SecurityException("no permission") })
        vm.onIntent(Intent.PermissionResult(true))
        assertTrue(vm.state.value.hasError)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun sendersMode_groupsBySender_andOpeningAConversationShowsItsMessages() {
        val more = inbox + SmsMessage(3, "VM-SWIGGY-S", "Your Swiggy order is delivered", now - 120_000, true)
        val vm = viewModel(flow { emit(more) })
        vm.onIntent(Intent.PermissionResult(true))
        assertTrue(vm.state.value.conversations.isEmpty()) // only built in Senders mode

        vm.onIntent(Intent.SetViewMode(ViewMode.SENDERS))
        assertEquals(listOf("SWIGGY", "BANK"), vm.state.value.conversations.map { it.key })
        assertEquals(2, vm.state.value.conversations.first().messages.size)

        vm.onIntent(Intent.OpenSender("SWIGGY"))
        assertEquals("SWIGGY", vm.state.value.thread!!.conversation.key)
        assertEquals(listOf(1L, 3L), vm.state.value.thread!!.sections.flatMap { it.items }.map { it.newest.id }.sorted())

        vm.onIntent(Intent.CloseThread)
        assertNull(vm.state.value.thread)
    }

    @Test
    fun moreFromThisSender_opensTheConversationFocusedOnTheMessage_andClosesTheSheet() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))
        vm.onIntent(Intent.OpenMessage(2))

        vm.onIntent(Intent.OpenThreadFor(2))

        val state = vm.state.value
        assertNull(state.detail)
        assertEquals("BANK", state.openThreadKey)
        assertEquals(2L, state.focusMessageId)
        assertEquals(1, state.thread!!.conversation.messages.size)
    }

    @Test
    fun archivingTheOnlyMessage_closesTheConversation() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))
        vm.onIntent(Intent.OpenThreadFor(2))

        vm.onIntent(Intent.Archive(2))

        assertNull(vm.state.value.openThreadKey)
        assertNull(vm.state.value.thread)
    }

    @Test
    fun switchingViewMode_closesAnOpenConversation() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))
        vm.onIntent(Intent.OpenThreadFor(1))

        vm.onIntent(Intent.SetViewMode(ViewMode.SENDERS))

        assertNull(vm.state.value.thread)
    }

    @Test
    fun extraFilters_unreadAndTimeWindow_andResetKeepsTheQuickFilter() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))

        vm.onIntent(Intent.SelectStatus(StatusFilter.UNREAD))
        assertEquals(listOf(2L), vm.visibleIds()) // message 2 is unread
        assertEquals(1, vm.state.value.filter.extraCount)

        vm.onIntent(Intent.SelectWindow(TimeWindow.TODAY))
        assertEquals(0, vm.state.value.feed.visibleCount) // the unread one is 3 days old

        vm.onIntent(Intent.SelectStatus(StatusFilter.ANY))
        assertEquals(listOf(1L), vm.visibleIds())

        vm.onIntent(Intent.SelectSmartFilter(SmartFilter.OTP))
        vm.onIntent(Intent.ResetExtras)
        assertEquals(SmartFilter.OTP, vm.state.value.filter.smart)
        assertEquals(0, vm.state.value.filter.extraCount)
    }

    @Test
    fun pinnedOnly_showsJustPinned() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))
        vm.onIntent(Intent.TogglePin(2))

        vm.onIntent(Intent.SelectStatus(StatusFilter.PINNED))

        assertEquals(listOf(2L), vm.visibleIds())
    }

    @Test
    fun retry_afterAFailure_loadsAgain() {
        var attempts = 0
        val vm = viewModel(flow { if (attempts++ == 0) throw SecurityException("boom") else emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))
        assertTrue(vm.state.value.hasError)

        vm.onIntent(Intent.Retry)

        assertFalse(vm.state.value.hasError)
        assertEquals(2, vm.state.value.feed.visibleCount)
    }

    @Test
    fun archivingFromTheSheet_closesTheSheet() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))
        vm.onIntent(Intent.OpenMessage(1))

        vm.onIntent(Intent.Archive(1))

        assertNull(vm.state.value.detail)
        assertEquals(listOf(2L), vm.visibleIds())
    }

    @Test
    fun summarySheet_opensAndCloses() {
        val vm = viewModel(MutableSharedFlow())
        vm.onIntent(Intent.OpenSummary)
        assertTrue(vm.state.value.showSummarySheet)
        vm.onIntent(Intent.CloseSummary)
        assertFalse(vm.state.value.showSummarySheet)
    }
}
