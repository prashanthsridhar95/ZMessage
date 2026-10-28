package com.zyke.message.presentation.messages.components

import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.feed.FeedItem
import com.zyke.message.domain.feed.GroupKind
import com.zyke.message.domain.feed.SectionId
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.messages.MessagesContract.SWIPE_HINT_KEY
import com.zyke.message.presentation.messages.MessagesContract.State
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageList(
    state: State,
    actions: FeedActions,
    modifier: Modifier = Modifier
) {
    val feed = state.feed
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())

    val drag = remember { MergeDragState() }
    val listState = rememberLazyListState()
    var listBounds by remember { mutableStateOf(Rect.Zero) }

    // Holding a payment near the top or bottom edge scrolls the list so it can be carried further.
    LaunchedEffect(drag.draggingId) {
        if (drag.draggingId == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val y = drag.pointer.y
            val step = when {
                y < listBounds.top + EDGE_PX -> -SCROLL_PX
                y > listBounds.bottom - EDGE_PX -> SCROLL_PX
                else -> 0f
            }
            if (step != 0f) {
                listState.scrollBy(step)
                drag.refreshHover()
            }
        }
    }

    CompositionLocalProvider(LocalMergeDrag provides drag) {
    LazyColumn(modifier.onGloballyPositioned { listBounds = it.boundsInRoot() }, state = listState) {
        if (feed.attention.isNotEmpty()) {
            item(key = "attention", contentType = "attention") {
                AttentionStrip(
                    feed.attention, today, actions,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateItem()
                )
            }
        }

        if (state.showSwipeHint) {
            item(key = "swipe-hint", contentType = "hint") {
                SwipeHint(
                    onDismiss = { actions.onDismissAttention(SWIPE_HINT_KEY) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateItem()
                )
            }
        }

        feed.sections.forEach { section ->
            stickyHeader(key = "section-${section.id}") {
                SectionHeader(
                    when (val id = section.id) {
                        SectionId.Pinned -> "Pinned"
                        is SectionId.Day -> DateFormatters.dayHeader(id.date, today)
                    },
                    Modifier.animateItem()
                )
            }

            items(section.items, key = { it.key }, contentType = { it::class }) { feedItem ->
                when (feedItem) {
                    is FeedItem.Single -> MessageItem(feedItem.message, state, actions, Modifier.animateItem())
                    is FeedItem.Group -> GroupedItem(
                        group = feedItem,
                        expanded = feedItem.key in state.expandedGroups,
                        state = state,
                        actions = actions,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
    }
}

private const val EDGE_PX = 160f
private const val SCROLL_PX = 28f

/** One-time tip that makes the hidden gestures discoverable. */
@Composable
private fun SwipeHint(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = "Tip: swipe a message right to archive, left to pin. Hold a payment and drop it on its twin to merge them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        AppButton("Got it", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
}

/** Several similar messages collapsed into the newest one plus a "+N earlier" toggle. */
@Composable
private fun GroupedItem(
    group: FeedItem.Group,
    expanded: Boolean,
    state: State,
    actions: FeedActions,
    modifier: Modifier = Modifier
) {
    val noun = when (group.kind) {
        GroupKind.OTP -> "code"
        GroupKind.DATA_USAGE -> "alert"
    }

    Column(modifier) {
        if (expanded) {
            group.messages.forEach { MessageItem(it, state, actions) }
        } else {
            MessageItem(group.newest, state, actions)
        }

        Text(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Show fewer" else "Show all") {
                    actions.onToggleGroup(group.key)
                }
                .padding(start = 92.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            text = if (expanded) {
                "Show less"
            } else {
                "+${group.hiddenCount} earlier $noun${if (group.hiddenCount > 1) "s" else ""} today"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        text = text,
        color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.Bold
    )
}
