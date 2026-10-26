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
private const val SWIPE_COMMIT_FRACTION = 0.5f

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
        // A swipe only counts once it has been dragged past half the row, so a brush or a slip does nothing.
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
            val (label, color, alignment) = when (state.targetValue) {
                SwipeToDismissBoxValue.StartToEnd ->
                    Triple(archiveLabel, MaterialTheme.colorScheme.primaryContainer, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart ->
                    Triple(pinLabel, MaterialTheme.colorScheme.tertiaryContainer, Alignment.CenterEnd)
                SwipeToDismissBoxValue.Settled -> Triple("", Color.Transparent, Alignment.Center)
            }
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
