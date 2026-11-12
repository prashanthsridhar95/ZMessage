package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.zyke.message.R
import com.zyke.message.domain.feed.RowSwipe
import com.zyke.message.domain.feed.SwipeAction
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.IconSize
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** How strong the background is before the swipe has gone far enough to count. */
private const val PENDING_ALPHA = 0.55f

/**
 * The same actions as the swipes, for a screen reader: the row announces them as custom actions ("Mark as read", "Archive",
 * "Pin"). Provided by [SwipeActions] and read by the row that carries the spoken summary (MessageRow), because only that
 * merged node is what TalkBack focuses.
 */
internal val LocalRowCustomActions = compositionLocalOf<List<CustomAccessibilityAction>> { emptyList() }

/**
 * Swipe right most of the way to mark the row read (or unread), left to archive (or restore). The row always snaps back;
 * the list update then removes it or changes its dot, which keeps this safe in filtered views, and a snackbar offers Undo.
 *
 * Crossing the commit line (see [RowSwipe.COMMIT_FRACTION]) gives a light tick and firms up the colour behind the row;
 * letting go earlier does nothing. Under reduced motion the row returns at once instead of springing back.
 *
 * On a payment row that holds its repeats ("+2 related") and on the newest row of a group of codes, the swipe is the
 * anchor message's: archive and restore apply to it alone (see [RowSwipe]). Pin has no swipe; it stays in the detail
 * sheet and in the screen-reader actions here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeActions(
    swipe: RowSwipe,
    isPinned: Boolean,
    canPin: Boolean,
    onAction: (SwipeAction) -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val motion = rememberMotion()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val currentSwipe by rememberUpdatedState(swipe)
    val act by rememberUpdatedState(onAction)
    val togglePin by rememberUpdatedState(onTogglePin)
    val reduced by rememberUpdatedState(motion.reduced)

    var rowWidth by remember { mutableFloatStateOf(0f) }
    // Filled in once the state exists; the confirm callback below needs to read where the row has been dragged to.
    val stateHolder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    fun dragFraction(): Float {
        val state = stateHolder[0] ?: return 0f
        val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
        return RowSwipe.fraction(offset, rowWidth)
    }

    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * RowSwipe.COMMIT_FRACTION },
        confirmValueChange = { value ->
            // Even a fast flick must have taken the row all the way across, or nothing happens.
            if (RowSwipe.committed(dragFraction())) {
                val action = when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> currentSwipe.toRight
                    SwipeToDismissBoxValue.EndToStart -> currentSwipe.toLeft
                    SwipeToDismissBoxValue.Settled -> null
                }
                if (action != null) act(action)
            }
            // Never settle on a dismissed value: the row comes back (instantly when motion is reduced).
            if (reduced) stateHolder[0]?.let { scope.launch { it.snapTo(SwipeToDismissBoxValue.Settled) } }
            false
        }
    )
    stateHolder[0] = state

    // One light tick as the swipe crosses the line where letting go would act.
    LaunchedEffect(state) {
        snapshotFlow { RowSwipe.committed(dragFraction()) }
            .distinctUntilChanged()
            .collect { crossed -> if (crossed) haptics.tick() }
    }

    val pinLabel = if (isPinned) "Unpin" else "Pin"
    val screenReaderActions = buildList {
        swipe.toRight?.let { action -> add(CustomAccessibilityAction(action.label) { act(action); true }) }
        add(CustomAccessibilityAction(swipe.toLeft.label) { act(swipe.toLeft); true })
        if (canPin) add(CustomAccessibilityAction(pinLabel) { togglePin(); true })
    }

    SwipeToDismissBox(
        state = state,
        modifier = modifier.onSizeChanged { rowWidth = it.width.toFloat() },
        enableDismissFromStartToEnd = swipe.toRight != null,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            // Follows the finger from the start; it firms up only once the row is all the way across.
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val action = when {
                toEnd -> swipe.toRight
                toStart -> swipe.toLeft
                else -> null
            }
            if (action != null) SwipeReveal(action, atStart = toEnd, committed = RowSwipe.committed(dragFraction()))
        }
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
            CompositionLocalProvider(LocalRowCustomActions provides screenReaderActions) { content() }
        }
    }
}

/** What is uncovered behind the row: the action's icon and its name, on the theme's own container colours. */
@Composable
private fun SwipeReveal(action: SwipeAction, atStart: Boolean, committed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val reading = action == SwipeAction.MARK_READ || action == SwipeAction.MARK_UNREAD
    val container: Color = if (reading) scheme.primaryContainer else scheme.secondaryContainer
    val ink: Color = if (reading) scheme.onPrimaryContainer else scheme.onSecondaryContainer
    val icon = when (action) {
        SwipeAction.MARK_READ -> R.drawable.baseline_check_24
        SwipeAction.MARK_UNREAD -> R.drawable.baseline_message_24
        SwipeAction.ARCHIVE, SwipeAction.RESTORE -> R.drawable.baseline_archive_24
    }
    Box(
        Modifier.fillMaxSize().background(if (committed) container else container.copy(alpha = PENDING_ALPHA)).padding(horizontal = Spacing.xl),
        contentAlignment = if (atStart) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // The row itself offers the same action to a screen reader, so this icon is decoration beside its label.
            val glyph: @Composable () -> Unit = {
                Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(IconSize.lg), colorFilter = ColorFilter.tint(ink))
            }
            if (atStart) glyph()
            Text(action.label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
            if (!atStart) glyph()
        }
    }
}
