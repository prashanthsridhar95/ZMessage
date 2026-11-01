package com.zyke.message.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.privacy.RevealTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one place that says whether hidden amounts are visible right now. Everything that shows money reads [revealed]
 * (through [LocalPrivacyMode]); anything that wants to show money calls [requestReveal], which asks for the phone's
 * screen lock first. Hoisted behind an interface so tests can fake it. Nothing here is ever stored.
 */
interface AmountReveal {
    /** True while amounts are visible (a window opened by [requestReveal] has not yet run out). */
    val revealed: StateFlow<Boolean>

    /** The elapsed-realtime reading at which the window ends; 0 when amounts are hidden. */
    val revealedUntil: StateFlow<Long>

    /** True when showing amounts asks for the screen lock (a lock exists and the setting is on). */
    val asksForAuthentication: Boolean

    fun remainingMillis(): Long

    /** Asks for the screen lock when needed, opens the window, then calls [onRevealed]. Does nothing if the person says no. */
    fun requestReveal(onRevealed: () -> Unit = {})

    /** Hides everything again at once. */
    fun hideNow()
}

/** Runs [block] now if amounts are already visible (the screen lock was just passed), otherwise after the lock is passed. */
fun AmountReveal.runWhenRevealed(block: () -> Unit) {
    if (revealed.value) block() else requestReveal(block)
}

/** The real thing. [authenticate] shows the system lock screen and reports whether it was passed. */
class TimedAmountReveal(
    private val scope: CoroutineScope,
    clock: () -> Long,
    private val authRequired: () -> Boolean,
    /** How long a reveal after the lock check lasts (the person's choice); null for until it is hidden. */
    private val windowMillis: () -> Long? = { RevealTimer.AUTHENTICATED_MILLIS },
    private val screenLockExists: () -> Boolean,
    private val authenticate: (onResult: (Boolean) -> Unit) -> Unit
) : AmountReveal {
    private val timer = RevealTimer(clock)
    private val _revealed = MutableStateFlow(false)
    private val _until = MutableStateFlow(0L)
    private var expiry: Job? = null
    override val revealed: StateFlow<Boolean> = _revealed.asStateFlow()
    override val revealedUntil: StateFlow<Long> = _until.asStateFlow()
    override val asksForAuthentication: Boolean get() = authRequired() && screenLockExists()

    override fun remainingMillis(): Long = timer.remainingMillis()

    override fun requestReveal(onRevealed: () -> Unit) {
        if (asksForAuthentication) {
            authenticate { passed -> if (passed) { open(authenticated = true); onRevealed() } }
        } else {
            open(authenticated = false)
            onRevealed()
        }
    }

    private fun open(authenticated: Boolean) {
        // After the lock check the person's chosen time applies; a plain tap (no screen lock) keeps its short window.
        val until = if (authenticated) timer.grantFor(windowMillis()) else timer.grant(false)
        _until.value = until
        _revealed.value = true
        expiry?.cancel()
        // A window with no end of its own is closed by hand or on leaving the app, never by a timer.
        expiry = if (timer.isOpenEnded) null else scope.launch { delay(timer.remainingMillis()); hideNow() }
    }

    override fun hideNow() {
        expiry?.cancel()
        timer.hide()
        _until.value = 0L
        _revealed.value = false
    }
}

/** Used where nothing is provided (previews): amounts are never revealed, nothing is asked. */
object NoAmountReveal : AmountReveal {
    override val revealed: StateFlow<Boolean> = MutableStateFlow(false)
    override val revealedUntil: StateFlow<Long> = MutableStateFlow(0L)
    override val asksForAuthentication: Boolean = false
    override fun remainingMillis(): Long = 0L
    override fun requestReveal(onRevealed: () -> Unit) = onRevealed()
    override fun hideNow() = Unit
}

val LocalAmountReveal = compositionLocalOf<AmountReveal> { NoAmountReveal }

/** "Amounts visible · hides in 0:45", shown only while a window is open; a tap hides them at once. */
@Composable
fun AmountsVisibleChip(modifier: Modifier = Modifier) {
    val privacy = LocalPrivacyMode.current
    val reveal = LocalAmountReveal.current
    val revealed by reveal.revealed.collectAsState()
    if (!privacy.enabled || !revealed) return
    val until by reveal.revealedUntil.collectAsState()
    var remaining by remember(until) { mutableLongStateOf(reveal.remainingMillis()) }
    LaunchedEffect(until) {
        while (true) { remaining = reveal.remainingMillis(); delay(250) }
    }
    val openEnded = until == RevealTimer.NO_END
    val label = RevealTimer.label(remaining)
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.widthIn(min = 48.dp)
                .semantics {
                    contentDescription = if (openEnded) "Amounts visible. Double tap to hide now" else "Amounts visible, hide in $label. Double tap to hide now"
                }
                .clickable(role = Role.Button, onClickLabel = "Hide amounts now") { reveal.hideNow() }
        ) {
            Text(if (openEnded) "Visible · tap to hide" else "Visible · $label", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
