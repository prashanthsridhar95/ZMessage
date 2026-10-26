package com.zyke.message.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.messages.MessagesContract.Effect
import com.zyke.message.presentation.messages.MessagesContract.Intent
import com.zyke.message.presentation.messages.MessagesContract.PermissionStatus
import com.zyke.message.presentation.messages.MessagesContract.State
import com.zyke.message.presentation.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MessagesViewModel(
    private val observeMessages: ObserveMessagesUseCase
) : MviViewModel<State, Intent, Effect>(State()) {

    private var observeJob: Job? = null

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted -> onScreenStarted(intent.permissionGranted)
            is Intent.PermissionResult -> onPermissionResult(intent.granted)
            Intent.RequestPermission -> emitEffect(Effect.LaunchPermissionRequest)
            Intent.ToggleFilters -> toggleFilters()
            is Intent.SelectFilter -> reduce {
                copy(selectedFilter = intent.category, sections = messages.toSections(intent.category))
            }
            is Intent.ToggleExpanded -> reduce {
                copy(
                    expandedIds = if (intent.messageId in expandedIds) {
                        expandedIds - intent.messageId
                    } else {
                        expandedIds + intent.messageId
                    }
                )
            }
            is Intent.CopyOtp -> emitEffect(Effect.CopyToClipboard("One-time Password", intent.code))
        }
    }

    private fun onScreenStarted(permissionGranted: Boolean) {
        when {
            permissionGranted -> onPermissionResult(true)
            currentState().permission == PermissionStatus.UNKNOWN -> emitEffect(Effect.LaunchPermissionRequest)
        }
    }

    private fun onPermissionResult(granted: Boolean) {
        if (granted) {
            reduce { copy(permission = PermissionStatus.GRANTED) }
            startObserving()
        } else {
            reduce { copy(permission = PermissionStatus.DENIED, isLoading = false) }
        }
    }

    private fun toggleFilters() = reduce {
        val show = !showFilters
        // Hiding the filter bar also clears the active filter.
        val filter = if (show) selectedFilter else null
        copy(showFilters = show, selectedFilter = filter, sections = messages.toSections(filter))
    }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        reduce { copy(isLoading = messages.isEmpty(), hasError = false) }

        observeJob = viewModelScope.launch {
            observeMessages()
                .catch { reduce { copy(isLoading = false, hasError = true) } }
                .collect { inbox ->
                    reduce {
                        copy(
                            isLoading = false,
                            hasError = false,
                            messages = inbox,
                            sections = inbox.toSections(selectedFilter)
                        )
                    }
                }
        }
    }

    class Factory(private val observeMessages: ObserveMessagesUseCase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            MessagesViewModel(observeMessages) as T
    }
}
