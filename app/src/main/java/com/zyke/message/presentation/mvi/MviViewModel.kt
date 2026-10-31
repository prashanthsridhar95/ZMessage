package com.zyke.message.presentation.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext
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

    fun onIntent(intent: I) {
        com.zyke.message.data.diag.DevLog.d("intent") { "${this::class.java.simpleName} <- ${intent!!::class.java.simpleName}" }
        try {
            handleIntent(intent)
        } catch (error: Throwable) {
            com.zyke.message.data.diag.DevLog.e("intent", "${this::class.java.simpleName} failed on ${intent!!::class.java.simpleName}", error)
            throw error
        }
    }

    protected abstract fun handleIntent(intent: I)

    protected fun reduce(reducer: S.() -> S) = _state.update { it.reducer() }

    protected fun currentState(): S = _state.value

    private val deriveRequests = Channel<Unit>(Channel.CONFLATED)
    private var deriveStarted = false

    /**
     * Starts the worker that works out the derived part of the state away from the main thread. [derive] reads a
     * snapshot of the state and returns a patch; the patch is applied to whatever the state is when the work is done,
     * so anything the person changed meanwhile is kept. Asking again while it runs is folded into one more run.
     * A run that takes long is noted in the developer log (never any message text).
     */
    protected fun startDeriving(name: String, context: CoroutineContext = Dispatchers.Default, derive: (S) -> (S.() -> S)) {
        if (deriveStarted) return
        deriveStarted = true
        viewModelScope.launch(context) {
            for (request in deriveRequests) {
                val started = System.nanoTime()
                val patch = try {
                    derive(_state.value)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    com.zyke.message.data.diag.DevLog.e("derive", "$name failed", error)
                    continue
                }
                _state.update { it.patch() }
                val ms = (System.nanoTime() - started) / 1_000_000
                if (ms > SLOW_STAGE_MS) com.zyke.message.data.diag.DevLog.d("slow") { "$name pipeline took $ms ms on ${Thread.currentThread().name}" }
            }
        }
    }

    /** Asks for the derived part to be worked out again from the state as it is now. */
    protected fun requestDerive() {
        deriveRequests.trySend(Unit)
    }

    private companion object {
        const val SLOW_STAGE_MS = 200L
    }

    protected fun emitEffect(effect: E) {
        viewModelScope.launch { _effects.send(effect) }
    }
}
