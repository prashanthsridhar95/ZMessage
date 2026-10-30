package com.zyke.message.presentation.messages

import com.zyke.message.domain.repository.NoMerges
import com.zyke.message.domain.repository.MergeRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.zyke.message.domain.conversation.ConversationBuilder
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.MutedSenderRepository
import com.zyke.message.domain.repository.NoMutedSenders
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.NoNicknames
import com.zyke.message.domain.repository.NoCategoryOverrides
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import com.zyke.message.presentation.messages.MessagesContract.Effect
import com.zyke.message.presentation.messages.MessagesContract.Intent
import com.zyke.message.presentation.messages.MessagesContract.PermissionStatus
import com.zyke.message.presentation.messages.MessagesContract.State
import com.zyke.message.presentation.messages.MessagesContract.ViewMode
import com.zyke.message.presentation.mvi.MviViewModel
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MessagesViewModel(
    private val observeMessages: ObserveMessagesUseCase,
    private val userStateRepository: UserMessageStateRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val categoryOverrides: CategoryOverrideRepository = NoCategoryOverrides,
    private val mutedSenders: MutedSenderRepository = NoMutedSenders,
    private val nicknames: NicknameRepository = NoNicknames,
    private val merges: MergeRepository = NoMerges
) : MviViewModel<State, Intent, Effect>(State(now = clock.instant(), userState = userStateRepository.state.value)) {

    private val zone: ZoneId get() = clock.zone
    private val conversationBuilder = ConversationBuilder()
    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            userStateRepository.state.collect { userState -> reduceFeed { copy(userState = userState) } }
        }
    }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.ScreenStarted -> onScreenStarted(intent.permissionGranted)
            is Intent.PermissionResult -> onPermissionResult(intent.granted)
            Intent.RequestPermission -> emitEffect(Effect.LaunchPermissionRequest)
            Intent.Retry ->
                if (currentState().permission == PermissionStatus.GRANTED) {
                    reduce { copy(hasError = false, isLoading = true) }
                    startObserving()
                } else {
                    emitEffect(Effect.LaunchPermissionRequest)
                }
            Intent.Tick -> reduceFeed { copy(now = clock.instant()) }

            is Intent.SetViewMode -> reduceFeed { copy(viewMode = intent.mode, openThreadKey = null, focusMessageId = null) }
            is Intent.OpenMessage -> openMessage(intent.messageId)
            Intent.CloseDetail -> reduce { copy(detailMessageId = null) }
            is Intent.OpenThreadFor -> openThreadFor(intent.messageId)
            is Intent.OpenSender -> reduceFeed { copy(openThreadKey = intent.key, focusMessageId = null) }
            Intent.CloseThread -> reduceFeed { copy(openThreadKey = null, focusMessageId = null) }

            is Intent.SelectSmartFilter -> reduceFeed { copy(filter = filter.copy(smart = intent.filter)) }
            is Intent.SelectCategory -> reduceFeed { copy(filter = filter.copy(category = intent.category)) }
            Intent.OpenFilters -> reduce { copy(showFilterSheet = true) }
            Intent.CloseFilters -> reduce { copy(showFilterSheet = false) }
            Intent.ResetFilters -> reduceFeed { copy(filter = MessageFilter.All) }
            Intent.ResetExtras -> reduceFeed { copy(filter = filter.withoutExtras()) }
            is Intent.SelectStatus -> reduceFeed { copy(filter = filter.copy(status = intent.status)) }
            is Intent.SelectWindow -> reduceFeed { copy(filter = filter.copy(window = intent.window)) }
            Intent.ToggleSearch -> reduceFeed {
                copy(isSearching = !isSearching, query = "")
            }
            is Intent.SetQuery -> reduceFeed { copy(query = intent.query) }

            is Intent.ToggleGroup -> reduce {
                copy(expandedGroups = expandedGroups.toggle(intent.key))
            }
            Intent.OpenSummary -> reduce { copy(showSummarySheet = true) }
            Intent.CloseSummary -> reduce { copy(showSummarySheet = false) }

            is Intent.CopyOtp -> {
                userStateRepository.markRead(intent.messageId)
                emitEffect(Effect.CopyToClipboard("One-time Password", intent.code))
            }
            is Intent.Archive -> {
                if (currentState().detailMessageId == intent.messageId) reduce { copy(detailMessageId = null) }
                userStateRepository.setArchived(intent.messageId, true)
                emitEffect(Effect.MessageArchived(intent.messageId))
            }
            is Intent.Unarchive -> userStateRepository.setArchived(intent.messageId, false)
            is Intent.ToggleMute -> currentState().messages.firstOrNull { it.id == intent.messageId }?.let { message ->
                mutedSenders.setMuted(SenderKey.of(message.message.sender), !message.isMuted)
            }
            is Intent.ArchiveMany -> {
                intent.ids.forEach { userStateRepository.setArchived(it, true) }
                emitEffect(Effect.ManyArchived(intent.ids))
            }
            is Intent.UnarchiveMany -> intent.ids.forEach { userStateRepository.setArchived(it, false) }
            is Intent.TogglePin -> userStateRepository.setPinned(
                intent.messageId,
                intent.messageId !in currentState().userState.pinnedIds
            )
            is Intent.RequestMerge -> if (intent.primaryId != intent.duplicateId) reduce { copy(mergeRequest = intent.primaryId to intent.duplicateId) }
            Intent.SwapMerge -> reduce { copy(mergeRequest = mergeRequest?.let { (primary, duplicate) -> duplicate to primary }) }
            Intent.ConfirmMerge -> {
                currentState().mergeRequest?.let { (primary, duplicate) -> merges.merge(primary, duplicate) }
                reduce { copy(mergeRequest = null, detailMessageId = null) }
            }
            Intent.CancelMerge -> reduce { copy(mergeRequest = null) }
            is Intent.Unmerge -> merges.unmerge(intent.duplicateId)
            is Intent.Rename -> currentState().messages.firstOrNull { it.id == intent.messageId }?.let { message ->
                val own = CategoryKey.messageKey(message.id)
                // Naming this one transaction and naming its sender or receiver are two separate things.
                if (intent.onlyThis) nicknames.set(own, intent.name) else CategoryKey.of(message)?.let { nicknames.set(it, intent.name) }
            }
            is Intent.SetMessageCategory -> categoryOverrides.set(CategoryKey.messageKey(intent.messageId), intent.category)
            is Intent.SetCategory -> currentState().messages.firstOrNull { it.id == intent.messageId }?.let { message ->
                val key = CategoryKey.of(message) ?: return@let
                val previous = message.category.takeIf { message.categoryOverridden }
                categoryOverrides.set(key, intent.category)
                if (intent.announce) {
                    emitEffect(Effect.CategoryChanged(message.id, CategoryKey.label(message), intent.category, previous))
                }
            }
            is Intent.DismissAttention -> userStateRepository.dismissAttention(intent.key, intent.forever)
            is Intent.OpenLink -> emitEffect(Effect.OpenLink(intent.url))
        }
    }

    private fun openMessage(messageId: Long) {
        if (currentState().messages.none { it.id == messageId }) return
        userStateRepository.markRead(messageId)
        reduce { copy(detailMessageId = messageId) }
    }

    private fun openThreadFor(messageId: Long) {
        val message = currentState().messages.firstOrNull { it.id == messageId } ?: return
        reduceFeed {
            copy(
                detailMessageId = null,
                openThreadKey = SenderKey.of(message.message.sender),
                focusMessageId = messageId
            )
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

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        reduce { copy(isLoading = messages.isEmpty(), hasError = false) }

        observeJob = viewModelScope.launch {
            observeMessages()
                .catch { reduceFeed { copy(isLoading = false, hasError = true) } }
                .collect { inbox ->
                    reduceFeed {
                        copy(
                            isLoading = false,
                            hasError = false,
                            messages = inbox,
                            now = clock.instant()
                        )
                    }
                }
        }
    }

    /** Applies [change] and rebuilds the derived [State.feed]. */
    private fun reduceFeed(change: State.() -> State) = reduce {
        val updated = change().withValidFilter()
        val query = if (updated.isSearching) updated.query else ""
        val thread = updated.openThreadKey?.let {
            conversationBuilder.thread(updated.messages, it, updated.userState, zone)
        }
        updated.copy(
            feed = FeedBuilder.build(
                messages = updated.messages,
                userState = updated.userState,
                filter = updated.filter,
                query = query,
                now = updated.now,
                zone = zone
            ),
            conversations = if (updated.viewMode == ViewMode.SENDERS) {
                conversationBuilder.conversations(updated.messages, updated.userState, query)
            } else {
                emptyList()
            },
            thread = thread
        ).let { built ->
            // A conversation that has no messages left (all archived) closes itself.
            if (built.openThreadKey != null && thread == null) built.copy(openThreadKey = null, focusMessageId = null) else built
        }
    }

    /** The Archived chip vanishes once nothing is archived, so a filter on it resets to All. */
    private fun State.withValidFilter(): State =
        if (selectedSmartFilter == SmartFilter.ARCHIVED && userState.archivedIds.isEmpty()) {
            copy(filter = filter.copy(smart = SmartFilter.ALL))
        } else {
            this
        }

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

    class Factory(
        private val observeMessages: ObserveMessagesUseCase,
        private val userStateRepository: UserMessageStateRepository,
        private val categoryOverrides: CategoryOverrideRepository = NoCategoryOverrides,
        private val mutedSenders: MutedSenderRepository = NoMutedSenders,
        private val nicknames: NicknameRepository = NoNicknames,
        private val merges: MergeRepository = NoMerges
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            MessagesViewModel(
                observeMessages, userStateRepository, categoryOverrides = categoryOverrides, mutedSenders = mutedSenders, nicknames = nicknames, merges = merges
            ) as T
    }
}
