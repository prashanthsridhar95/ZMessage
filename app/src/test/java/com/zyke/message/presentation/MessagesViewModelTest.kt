package com.zyke.message.presentation

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.messages.MessagesContract.Effect
import com.zyke.message.presentation.messages.MessagesContract.Intent
import com.zyke.message.presentation.messages.MessagesContract.PermissionStatus
import com.zyke.message.presentation.messages.MessagesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {

    private class FakeRepository(val inbox: Flow<List<SmsMessage>>) : SmsRepository {
        override fun observeInbox() = inbox
    }

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L
    private val inbox = listOf(
        SmsMessage(1, "SWIGGY", "Your Swiggy order is on the way", now, true),
        SmsMessage(2, "AD-BANK", "123456 is your OTP", now - 3 * day, false)
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(source: Flow<List<SmsMessage>>) = MessagesViewModel(
        ObserveMessagesUseCase(FakeRepository(source), MessageClassifier(), Dispatchers.Unconfined)
    )

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
    fun grantedPermission_loadsAndGroupsMessages() {
        val vm = viewModel(flow { emit(inbox) })

        vm.onIntent(Intent.ScreenStarted(permissionGranted = true))

        val state = vm.state.value
        assertEquals(PermissionStatus.GRANTED, state.permission)
        assertFalse(state.isLoading)
        assertEquals(2, state.messages.size)
        assertEquals(2, state.sections.size)
        assertEquals(1L, state.sections.first().messages.single().id)
    }

    @Test
    fun filter_narrowsSections_andHidingFiltersClearsIt() {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.PermissionResult(true))

        vm.onIntent(Intent.ToggleFilters)
        vm.onIntent(Intent.SelectFilter(MessageCategory.OTP))
        assertEquals(listOf(2L), vm.state.value.sections.flatMap { it.messages }.map { it.id })

        vm.onIntent(Intent.ToggleFilters)
        assertEquals(null, vm.state.value.selectedFilter)
        assertEquals(2, vm.state.value.sections.sumOf { it.messages.size })
    }

    @Test
    fun toggleExpanded_addsAndRemovesId() {
        val vm = viewModel(flow { emit(inbox) })

        vm.onIntent(Intent.ToggleExpanded(2))
        assertEquals(setOf(2L), vm.state.value.expandedIds)
        vm.onIntent(Intent.ToggleExpanded(2))
        assertTrue(vm.state.value.expandedIds.isEmpty())
    }

    @Test
    fun copyOtp_emitsClipboardEffect() = runTest {
        val vm = viewModel(flow { emit(inbox) })
        vm.onIntent(Intent.CopyOtp("123456"))
        assertEquals(Effect.CopyToClipboard("One-time Password", "123456"), vm.effects.first())
    }

    @Test
    fun repositoryFailure_surfacesAsError() {
        val vm = viewModel(flow { throw SecurityException("no permission") })
        vm.onIntent(Intent.PermissionResult(true))
        assertTrue(vm.state.value.hasError)
        assertFalse(vm.state.value.isLoading)
    }
}
