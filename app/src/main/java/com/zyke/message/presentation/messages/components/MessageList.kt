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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextOverflow
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.motion.AppearVisibility
import com.zyke.message.presentation.motion.EnterGate
import com.zyke.message.presentation.motion.enterOnce
import com.zyke.message.presentation.motion.itemMotion
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.motion.rememberEnterGate
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.TypeRole
import com.zyke.message.presentation.theme.tonalFill
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
import com.zyke.message.presentation.common.Text
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
    modifier: Modifier = Modifier,
    /** Made above the loading state (see rememberEnterGate), so the first rows enter once and never again. */
    gate: EnterGate = rememberEnterGate()
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
                    Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).then(itemMotion())
                )
            }
        }

        if (state.showSwipeHint) {
            item(key = "swipe-hint", contentType = "hint") {
                SwipeHint(
                    onDismiss = { actions.onDismissAttention(SWIPE_HINT_KEY) },
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).then(itemMotion())
                )
            }
        }

        // The first rows of the first showing enter one after the other (enterOnce); the index counts rows, not headers.
        var rowsBefore = 0
        feed.sections.forEach { section ->
            stickyHeader(key = "section-${section.id}", contentType = "header") {
                SectionHeader(
                    when (val id = section.id) {
                        SectionId.Pinned -> "Pinned"
                        is SectionId.Day -> DateFormatters.dayHeader(id.date, today)
                    },
                    itemMotion()
                )
            }

            val rows = section.items.distinctBy { it.key }
            val firstRow = rowsBefore
            rowsBefore += rows.size
            itemsIndexed(rows, key = { _, it -> it.key }, contentType = { _, it -> it::class }) { index, feedItem ->
                // Item motion (rows that come and go, move up or fade out) and the entrance share one modifier chain.
                val rowModifier = itemMotion().enterOnce(gate, firstRow + index)
                when (feedItem) {
                    is FeedItem.Single -> MessageItem(feedItem.message, state, actions, rowModifier, related = feedItem.related)
                    is FeedItem.Group -> GroupedItem(
                        group = feedItem,
                        expanded = feedItem.key in state.expandedGroups,
                        state = state,
                        actions = actions,
                        modifier = rowModifier
                    )
                }
            }
        }

        // Only when the filters in force leave something out of All: "3 hidden by filters · Show". Nothing is drawn otherwise.
        if (feed.hiddenByFilters > 0 && !state.isSearching) {
            item(key = "hidden-by-filters", contentType = "hidden-footer") {
                HiddenByFilters(feed.hiddenByFilters, actions.onResetFilters, itemMotion())
            }
        }
    }
    }
}

/** One quiet line under the list: how many messages the filters hide, and a way to show them. */
@Composable
internal fun HiddenByFilters(count: Int, onShow: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressFeedback(source)
            .clickable(
                interactionSource = source, indication = LocalIndication.current,
                role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Clear filters and show them", onClick = onShow
            )
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$count hidden by filters \u00B7 ",
            style = TypeRole.caption,
            color = mutedColor(),
            maxLines = 1
        )
        Text(text = "Show", style = TypeRole.caption, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
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
            .background(tonalFill(Tonal.Quiet), AppShapes.chip)
            .padding(start = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = "Tip: swipe a message right to mark it read, left to archive. Hold a payment and drop it on its twin to merge them.",
            style = TypeRole.caption,
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

    val source = remember { MutableInteractionSource() }
    Column(modifier) {
        // The newest message is always there; the earlier ones open beneath it and close again.
        MessageItem(group.newest, state, actions)
        AppearVisibility(expanded) {
            Column {
                group.messages.drop(1).forEach { MessageItem(it, state, actions) }
            }
        }

        Text(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pressFeedback(source)
                .clickable(
                    interactionSource = source, indication = LocalIndication.current,
                    role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (expanded) "Show fewer" else "Show all"
                ) { actions.onToggleGroup(group.key) }
                .padding(start = RowTextInset, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.md),
            text = if (expanded) {
                "Show less"
            } else {
                "+${group.hiddenCount} earlier $noun${if (group.hiddenCount > 1) "s" else ""} today"
            },
            style = TypeRole.caption,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** A day's label above its rows: the spaced section label, on the page colour so rows slide under it while it sticks. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.xs)
            .heading(),
        text = text.uppercase(),
        style = TypeRole.section,
        color = mutedColor(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
