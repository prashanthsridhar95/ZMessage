package com.zyke.message.presentation.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Minimal MVI base: the view sends [I]ntents, the model reduces them into an immutable [S]tate
 * and emits one-shot [E]ffects (things that must not be replayed, like toasts or permission requests).
 */
abstract class MviViewModel<S, I, E>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    fun onIntent(intent: I) = handleIntent(intent)

    protected abstract fun handleIntent(intent: I)

    protected fun reduce(reducer: S.() -> S) = _state.update { it.reducer() }

    protected fun currentState(): S = _state.value

    protected fun emitEffect(effect: E) {
        viewModelScope.launch { _effects.send(effect) }
    }
}
