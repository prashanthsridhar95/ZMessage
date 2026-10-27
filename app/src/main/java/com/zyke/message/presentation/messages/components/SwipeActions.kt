package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** How far across the row a swipe must go before it takes effect. */
private const val SWIPE_COMMIT_FRACTION = 0.75f

/** How strong the background is before the swipe has gone far enough to count. */
private const val PENDING_ALPHA = 0.55f

/**
 * Swipe right to archive (or restore), swipe left to pin. The row always snaps back;
 * the list update then removes or moves it, which keeps this safe in filtered views.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeActions(
    isArchived: Boolean,
    isPinned: Boolean,
    onArchiveOrRestore: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val archiveOrRestore by rememberUpdatedState(onArchiveOrRestore)
    val togglePin by rememberUpdatedState(onTogglePin)

    val state = rememberSwipeToDismissBoxState(
        // The action shows from the first pixel, but only counts once the row is dragged three quarters across.
        positionalThreshold = { totalDistance -> totalDistance * SWIPE_COMMIT_FRACTION },
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> archiveOrRestore()
                SwipeToDismissBoxValue.EndToStart -> togglePin()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        }
    )

    val archiveLabel = if (isArchived) "Restore" else "Archive"
    val pinLabel = if (isPinned) "Unpin" else "Pin"

    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics {
            customActions = buildList {
                add(CustomAccessibilityAction(archiveLabel) { archiveOrRestore(); true })
                if (!isArchived) add(CustomAccessibilityAction(pinLabel) { togglePin(); true })
            }
        },
        enableDismissFromEndToStart = !isArchived,
        backgroundContent = {
            // Follows the finger from the start; it firms up once letting go would apply the action.
            val committed = state.targetValue != SwipeToDismissBoxValue.Settled
            val (label, base, alignment) = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd ->
                    Triple(archiveLabel, MaterialTheme.colorScheme.primaryContainer, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart ->
                    Triple(pinLabel, MaterialTheme.colorScheme.tertiaryContainer, Alignment.CenterEnd)
                SwipeToDismissBoxValue.Settled -> Triple("", Color.Transparent, Alignment.Center)
            }
            val color = if (committed) base else base.copy(alpha = PENDING_ALPHA)
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                Text(label, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}
