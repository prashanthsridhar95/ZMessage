package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import java.math.BigDecimal

/**
 * Where a drag of one payment onto another is: which one is held, where the finger is, and which one it is over. The
 * list registers each payment's place on screen so the one under the finger can be found.
 */
@Stable
class MergeDragState {
    var draggingId by mutableStateOf<Long?>(null)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var hoverId by mutableStateOf<Long?>(null)
        private set

    private val bounds = HashMap<Long, Rect>()

    fun register(id: Long, rect: Rect) { bounds[id] = rect }
    fun forget(id: Long) { bounds.remove(id) }

    fun start(id: Long, localStart: Offset) {
        draggingId = id
        offset = Offset.Zero
        pointer = (bounds[id]?.topLeft ?: Offset.Zero) + localStart
        refreshHover()
    }

    fun drag(delta: Offset) {
        offset += delta
        pointer += delta
        refreshHover()
    }

    /** Looks again at what is under the finger; the list may have scrolled since the finger last moved. */
    fun refreshHover() {
        val held = draggingId
        hoverId = if (held == null) null else bounds.entries.firstOrNull { it.key != held && it.value.contains(pointer) }?.key
    }

    /** Lets go: reports the pair when the finger is over another payment. */
    fun end(onDrop: (primaryId: Long, duplicateId: Long) -> Unit) {
        val target = hoverId
        val held = draggingId
        cancel()
        if (target != null && held != null) onDrop(target, held)
    }

    fun cancel() {
        draggingId = null
        hoverId = null
        offset = Offset.Zero
    }
}

val LocalMergeDrag = compositionLocalOf<MergeDragState?> { null }

/**
 * Touch and hold a payment, then drag it onto another to say they are one transaction. The held one lifts and follows
 * the finger; the one under it is outlined. Swiping to archive or pin still works, since the hold has to come first.
 */
fun Modifier.mergeDraggable(
    id: Long,
    state: MergeDragState?,
    enabled: Boolean,
    onDrop: (primaryId: Long, duplicateId: Long) -> Unit
): Modifier = composed {
    if (state == null || !enabled) return@composed Modifier
    val haptics = LocalHapticFeedback.current
    val held = state.draggingId == id
    val over = state.draggingId != null && state.hoverId == id
    DisposableEffect(id) { onDispose { state.forget(id) } }

    Modifier
        .onGloballyPositioned { state.register(id, it.boundsInRoot()) }
        .pointerInput(id) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.start(id, it)
                },
                onDrag = { change, delta ->
                    change.consume()
                    state.drag(delta)
                },
                onDragEnd = { state.end(onDrop) },
                onDragCancel = { state.cancel() }
            )
        }
        .zIndex(if (held) 1f else 0f)
        .graphicsLayer {
            if (held) {
                translationX = state.offset.x
                translationY = state.offset.y
                scaleX = HELD_SCALE
                scaleY = HELD_SCALE
                shadowElevation = HELD_SHADOW
                alpha = HELD_ALPHA
            }
        }
        .then(if (over) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
}

private const val HELD_SCALE = 1.03f
private const val HELD_SHADOW = 36f
private const val HELD_ALPHA = 0.96f

/**
 * Asks whether two messages are one transaction. It shows both, which stays and which joins it (swappable), says what
 * it will do to the totals, and warns when the two do not look alike.
 */
@Composable
internal fun MergeSheet(
    primary: ClassifiedMessage,
    duplicate: ClassifiedMessage,
    onSwap: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val a = primary.content as? MessageContent.Transaction
    val b = duplicate.content as? MessageContent.Transaction
    val amountA = a?.amount?.toBigDecimalOrNull()
    val amountB = b?.amount?.toBigDecimalOrNull()
    val differentAmount = amountA != null && amountB != null && amountA.compareTo(amountB) != 0
    val differentDirection = a != null && b != null && a.isReceived != b.isReceived

    FormSheet(
        title = "Same transaction?",
        onDismiss = onCancel,
        primaryLabel = "Merge",
        primaryEnabled = true,
        onPrimary = onConfirm,
        secondaryLabel = "Not the same",
        onSecondary = onCancel
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MergeCard("STAYS", primary)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("then this joins it", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = mutedColor())
                AppButton("Swap", onSwap, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
            MergeCard("JOINS IT", duplicate)
        }
        when {
            differentDirection -> Caption("One is money in and the other money out", color = MaterialTheme.colorScheme.error)
            differentAmount -> Caption(
                "Amounts differ: ${DisplayFormatters.rupees(amountA!!)} and ${DisplayFormatters.rupees(amountB!!)}",
                color = MaterialTheme.colorScheme.error
            )
            amountB != null && b != null ->
                Caption("${if (b.isReceived) "Received" else "Spent"} total goes down by ${DisplayFormatters.rupees(amountB)}")
        }
    }
}

@Composable
private fun MergeCard(label: String, message: ClassifiedMessage) {
    val tx = message.content as? MessageContent.Transaction
    FintechCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption(label)
                Text(message.merchant.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Caption("${DisplayFormatters.listStamp(message.message.date)} · ${message.message.sender}", maxLines = 1)
            }
            tx?.let {
                Text(
                    (if (it.isReceived) "+ " else "") + DisplayFormatters.rupees(it.amount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
        Text(
            message.message.body.replace(Regex("\\s+"), " "),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The messages merged into this one, each with a way to take it out again. */
@Composable
internal fun MergedMessages(item: ClassifiedMessage, onUnmerge: (Long) -> Unit) {
    if (item.mergedMessages.isEmpty()) return
    FintechCard {
        Caption("ALSO COUNTED HERE")
        item.mergedMessages.forEach { merged ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(merged.sender, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Caption(DateFormatters.detailTimestamp(merged.date))
                    Text(
                        merged.body.replace(Regex("\\s+"), " "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AppButton("Unmerge", { onUnmerge(merged.id) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    }
}

/** "Looks like the same payment": other messages with the same amount around the same time, each one tap from merging. */
@Composable
internal fun MergeSuggestions(item: ClassifiedMessage, candidates: List<ClassifiedMessage>, onMerge: (primaryId: Long, duplicateId: Long) -> Unit) {
    if (candidates.isEmpty()) return
    FintechCard {
        Caption("MAYBE THE SAME PAYMENT")
        candidates.forEach { other ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        other.message.sender,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Caption(DateFormatters.dayHeader(java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(other.message.date), java.time.ZoneId.systemDefault()), java.time.LocalDate.now()) + " · " + DisplayFormatters.time(other.message.date), maxLines = 1)
                }
                AppButton("Merge", { onMerge(item.id, other.id) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    }
}
