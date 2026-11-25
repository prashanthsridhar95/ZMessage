package com.zyke.message.presentation.home

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.conversation.SenderLabel
import com.zyke.message.domain.triage.TriageCard
import com.zyke.message.domain.triage.TriageDeck
import com.zyke.message.domain.triage.TriageGestures
import com.zyke.message.domain.triage.TriageMove
import com.zyke.message.domain.triage.TriageTone
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.CapFontScale
import com.zyke.message.presentation.common.DeckDots
import com.zyke.message.presentation.common.ChromeScale
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.SegmentedTabs
import com.zyke.message.presentation.common.SenderTitle
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.categoryText
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.privateSpeech
import com.zyke.message.presentation.common.rememberLedgerActions
import com.zyke.message.presentation.common.scaledLines
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.messages.components.CategorySheet
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.FeedActions
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.MotionSpecs
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppColors
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.TypeRole
import com.zyke.message.presentation.theme.tonalFill
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How much of each card behind the front one peeks out above it. */
private val DECK_PEEK = 10.dp

private const val TILT_DEGREES = 8f

/** The width of the up and down keys in the control bar. */
private val ARROW_KEY = 52.dp

/** How firm the swipe tint gets at the line (the tints themselves are already quiet, so it can reach full). */
private const val HINT_ALPHA = 1f
private const val STACK_SCALE_STEP = 0.06f

/** A drag moves the card with the finger only after this many px, and then on one axis only: sideways acts, vertical moves. */
private const val AXIS_LOCK_PX = 12f

/**
 * Where a card is while the finger is on it and while it leaves. [dragX] and [dragY] are read only while drawing, so a drag does not
 * recompose the cards. The card that left ([leaving]) is drawn flying away on top while the card that was behind it grows forward
 * into its place ([grow], 0 to 1); the data has already moved on by then (the card is read or archived, or the cursor has stepped),
 * so nothing waits for an animation: the next card can be acted on at once.
 */
@Stable
internal class TriageDeckState {
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)
    var widthPx by mutableFloatStateOf(1f)
    var heightPx by mutableFloatStateOf(1f)
    var grow by mutableFloatStateOf(1f)
    var leaving by mutableStateOf<TriageCard?>(null)
    var leaveX by mutableFloatStateOf(0f)
    var leaveY by mutableFloatStateOf(0f)
    private var axis = 0 // 0 undecided, 1 sideways, 2 vertical
    private var accX = 0f
    private var accY = 0f
    private var job: Job? = null

    fun startDrag() {
        axis = 0
        accX = 0f
        accY = 0f
        job?.cancel()
        // A card that was still flying off is let go; the drag starts from where the front card is now.
        finishLeaving()
    }

    fun dragBy(dx: Float, dy: Float) {
        if (axis == 0) {
            accX += dx
            accY += dy
            if (abs(accX) < AXIS_LOCK_PX && abs(accY) < AXIS_LOCK_PX) return
            axis = if (abs(accX) >= abs(accY)) 1 else 2
            dragX = if (axis == 1) accX else 0f
            dragY = if (axis == 2) accY else 0f
            return
        }
        if (axis == 1) dragX += dx else dragY += dy
    }

    private fun finishLeaving() {
        leaving = null
        grow = 1f
    }

    /** The card sprang back (or was never moved): returns to rest with the gentle spring, or at once when motion is reduced. */
    fun springBack(scope: CoroutineScope, motion: MotionSpecs) {
        job?.cancel()
        val fromX = dragX
        val fromY = dragY
        if (motion.reduced) {
            dragX = 0f
            dragY = 0f
            return
        }
        job = scope.launch {
            coroutineScope {
                launch { animate(fromX, 0f, animationSpec = motion.gentle()) { v, _ -> dragX = v } }
                launch { animate(fromY, 0f, animationSpec = motion.gentle()) { v, _ -> dragY = v } }
            }
        }
    }

    /**
     * The front card ([card]) leaves in the direction of [move]. [apply] changes the data (acts on the card or steps the cursor) at
     * once; the card flies on as an overlay and the next one grows forward. Instant when motion is reduced.
     */
    fun fling(scope: CoroutineScope, move: TriageMove, card: TriageCard, motion: MotionSpecs, apply: () -> Unit) {
        job?.cancel()
        finishLeaving()
        val fromX = dragX
        val fromY = dragY
        dragX = 0f
        dragY = 0f
        apply()
        if (motion.reduced) return
        leaving = card
        leaveX = fromX
        leaveY = fromY
        grow = 0f
        val toX = when (move) {
            TriageMove.SWEEP -> -widthPx * 1.25f
            TriageMove.KEEP -> widthPx * 1.25f
            else -> fromX
        }
        val toY = when (move) {
            TriageMove.NEXT -> heightPx * 1.1f
            TriageMove.PREVIOUS -> -heightPx * 1.1f
            else -> fromY
        }
        val spec = motion.tween<Float>(Motion.STANDARD_MS, Motion.Emphasized)
        job = scope.launch {
            coroutineScope {
                launch { animate(fromX, toX, animationSpec = spec) { v, _ -> leaveX = v } }
                launch { animate(fromY, toY, animationSpec = spec) { v, _ -> leaveY = v } }
                launch { animate(0f, 1f, animationSpec = spec) { v, _ -> grow = v } }
            }
            finishLeaving()
        }
    }
}

/**
 * The stack of cards you go through. [front] is the card in hand; behind it two plain slabs peek out (they are not the next
 * cards, only the sense of more to come). Drag the front card: sideways it follows the finger with a tilt and a tint that says what
 * letting go does (left sweeps it away, right keeps it); past 30 percent of the width, or with a flick, it flies off. Vertically it
 * only moves around the deck: down to the next card, up to the previous one (the cursor wraps). Everything here is also a button in the
 * control bar and an action for a screen reader. With reduced motion or Lite mode there is no tilt and no flying: the swap is instant.
 */
@Composable
internal fun TriageDeck(
    front: TriageCard?,
    count: Int,
    state: TriageDeckState,
    onMove: (TriageMove) -> Unit,
    modifier: Modifier = Modifier,
    face: @Composable (TriageCard, Modifier, Boolean) -> Unit
) {
    val motion = rememberMotion()
    val reduce = motion.reduced
    val scope = rememberCoroutineScope()
    val peekPx = with(LocalDensity.current) { DECK_PEEK.toPx() }
    val currentMove by rememberUpdatedState(onMove)
    val currentCount by rememberUpdatedState(count)
    val currentReduce by rememberUpdatedState(reduce)
    val frame = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val slabNear = lerp(frame, ink, 0.07f)
    val slabFar = lerp(frame, ink, 0.12f)
    // A hairline on each slab keeps its edge readable on true black and on white, where a tint step alone nearly vanishes.
    val slabEdge = ink.copy(alpha = 0.08f)
    Box(
        modifier
            .onSizeChanged { state.widthPx = it.width.toFloat().coerceAtLeast(1f); state.heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = { tracker.resetTracking(); state.startDrag() },
                    onDrag = { change, amount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        state.dragBy(amount.x, amount.y)
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        val move = TriageGestures.decide(state.dragX, state.dragY, state.widthPx, state.heightPx, v.x, v.y, currentCount)
                        if (move == TriageMove.NONE) state.springBack(scope, motion) else currentMove(move)
                    },
                    onDragCancel = { state.springBack(scope, motion) }
                )
            }
    ) {
        // (No early return from this lambda: a non-local return here makes the Kotlin 2.0 compiler emit a class D8 cannot dex.)
        if (front != null) {
        // The two slabs behind. Their place follows the drag (they come forward as the front card leaves) and the grow of the card that
        // took the front; the formula is the same one the Accounts deck uses.
        if (count >= 3) {
            Box(Modifier.fillMaxSize().graphicsLayer { stackSlot(2f - dragProgress(state) + (1f - state.grow), peekPx) }.background(slabFar, AppShapes.card).border(1.dp, slabEdge, AppShapes.card))
        }
        if (count >= 2) {
            Box(Modifier.fillMaxSize().graphicsLayer { stackSlot(1f - dragProgress(state) + (1f - state.grow), peekPx) }.background(slabNear, AppShapes.card).border(1.dp, slabEdge, AppShapes.card))
        }
        androidx.compose.runtime.key(front.messageId) {
            face(
                front,
                Modifier.fillMaxSize().graphicsLayer {
                    stackSlot(1f - state.grow, peekPx)
                    translationX = state.dragX
                    translationY = state.dragY
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    if (!currentReduce) rotationZ = TILT_DEGREES * (state.dragX / state.widthPx).coerceIn(-1.25f, 1.25f)
                },
                true
            )
        }
        val gone = state.leaving
        if (gone != null) {
            androidx.compose.runtime.key("leaving-${gone.messageId}") {
                face(
                    gone,
                    Modifier.fillMaxSize().graphicsLayer {
                        translationX = state.leaveX
                        translationY = state.leaveY
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        rotationZ = TILT_DEGREES * (state.leaveX / state.widthPx).coerceIn(-1.25f, 1.25f)
                    }.clearAndSetSemantics { },
                    false
                )
            }
        }
        }
    }
}

private fun dragProgress(state: TriageDeckState): Float = (abs(state.dragX) / (state.widthPx * 0.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)

/** How far behind the front card a card in [slot] sits: smaller and tucked up so only a sliver of its top shows. */
private fun GraphicsLayerScope.stackSlot(slot: Float, peekPx: Float) {
    val scale = 1f - STACK_SCALE_STEP * slot
    transformOrigin = TransformOrigin(0.5f, 1f)
    scaleX = scale
    scaleY = scale
    translationY = -(peekPx * slot + size.height * (1f - scale))
}

/**
 * Home's triage page: Fresh or Quiet, the stack chips, the title, the deck, and the control bar. Acting on a card is optimistic (the
 * card is off the deck and Home's count has dropped before anything is saved), and every action offers Undo in the snackbar.
 */
@Composable
internal fun TriageScreen(
    state: State,
    startQuiet: Boolean,
    startStack: String?,
    onIntent: (Intent) -> Unit,
    onClose: () -> Unit
) {
    val deck = state.shownDeck
    var quiet by rememberSaveable { mutableStateOf(startQuiet) }
    var chip by rememberSaveable { mutableStateOf(startStack) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var categoryFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val cards = remember(deck, quiet, chip) { deck.cards(quiet, chip) }
    val i = TriageGestures.afterRemoval(index, cards.size)
    val front = cards.getOrNull(i)
    val deckState = remember { TriageDeckState() }
    val motion = rememberMotion()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val ledger = rememberLedgerActions()
    val byId = remember(state.messages) { state.messages.associateBy { it.id } }

    val act: (TriageMove) -> Unit = { move ->
        val card = front
        if (card != null && move != TriageMove.NONE && !(cards.size < 2 && (move == TriageMove.NEXT || move == TriageMove.PREVIOUS))) {
            haptics.tick()
            deckState.fling(scope, move, card, motion) {
                when (move) {
                    TriageMove.SWEEP -> {
                        onIntent(Intent.TriageSweep(card.messageId))
                        ledger.withUndo("Archived · ${card.headline}") { onIntent(Intent.TriageUndo(listOf(card.messageId), swept = true)) }
                    }
                    TriageMove.KEEP -> {
                        onIntent(Intent.TriageGotIt(card.allIds))
                        ledger.withUndo("Marked read · ${card.headline}") { onIntent(Intent.TriageUndo(card.allIds, swept = false)) }
                    }
                    TriageMove.NEXT -> index = TriageGestures.next(i, cards.size)
                    TriageMove.PREVIOUS -> index = TriageGestures.previous(i, cards.size)
                    TriageMove.NONE -> Unit
                }
            }
        } else if (move != TriageMove.NONE) deckState.springBack(scope, motion)
    }

    val title = when {
        chip != null -> com.zyke.message.domain.triage.TriageStackSpec.byId(chip.orEmpty())?.title ?: "Your deck"
        quiet -> "Quiet"
        else -> "All stacks"
    }
    AppPage(
        title = "Your deck",
        onBack = onClose,
        scrollable = false,
        footer = { ControlBar(enabled = front != null, canMove = cards.size > 1, onMove = act) }
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = Spacing.lg)) {
                SegmentedTabs(
                    listOf("Fresh · ${deck.toGo}", "Quiet · ${deck.quietCount}"), if (quiet) 1 else 0,
                    { quiet = it == 1; chip = null; index = 0 }
                )
            }
            StackChips(deck, quiet, chip, cards.size) { chip = it; index = 0 }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.Bottom) {
                Text(
                    title, Modifier.weight(1f).heading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (front != null) Caption(TriageGestures.counter(i, cards.size), Modifier.padding(start = Spacing.md, bottom = Spacing.xs))
            }
            if (front == null) {
                val next = deck.stacks.firstOrNull { it.isQuiet == quiet && it.items.isNotEmpty() && it.id != chip }
                Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    EmptyState(
                        if (next != null) "Stack cleared" else "All caught up",
                        if (next != null) "Next up: ${next.title}, ${next.newCount} new." else "Nothing left here. Cleared cards stay in Messages.",
                        icon = R.drawable.baseline_check_24
                    )
                    if (next != null) AppButton("Next stack", { chip = next.id; index = 0 }, Modifier.fillMaxWidth())
                    AppButton("Back to Home", onClose, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
                }
            } else {
                TriageDeck(
                    front, cards.size, deckState, act,
                    Modifier.weight(1f).fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = DECK_PEEK * 2 + Spacing.sm, bottom = Spacing.lg)
                ) { card, m, isFront ->
                    CardFace(
                        card, byId[card.messageId], i, cards.size, if (isFront) deckState else null, m, act,
                        onCategory = { categoryFor = card.messageId },
                        onOpen = { onIntent(Intent.PeekMessage(card.messageId)) }
                    )
                }
                // Where you are in the stack, the same dots the Accounts deck shows (they widen and brighten in 160 ms; the counter above says it in words).
                if (cards.size > 1) {
                    Box(Modifier.fillMaxWidth().padding(bottom = Spacing.md), contentAlignment = Alignment.Center) {
                        DeckDots(cards.size, i, active = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    categoryFor?.let { id ->
        val message = byId[id]
        val card = cards.firstOrNull { it.messageId == id } ?: front
        if (message != null) {
            val actions = remember(onIntent) {
                FeedActions.None.copy(
                    onSetCategory = { messageId, category -> onIntent(Intent.SetCategory(messageId, category)) },
                    onSetMessageCategory = { messageId, category -> onIntent(Intent.SetMessageCategory(messageId, category)) }
                )
            }
            CategorySheet(message, actions, message.content is MessageContent.Transaction, ledger, null, card?.title ?: message.merchant.displayName) { categoryFor = null }
        } else categoryFor = null
    }
}

/** All, then the stacks on this side that have cards, each with its count; the chosen stack stays even when it has just been cleared. */
@Composable
private fun StackChips(deck: TriageDeck, quiet: Boolean, chosen: String?, total: Int, onChoose: (String?) -> Unit) {
    val stacks = deck.stacks.filter { it.isQuiet == quiet && (it.items.isNotEmpty() || it.id == chosen) }
    val allCount = deck.cards(quiet).size
    ChromeScale {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            TriageChip("All · $allCount", chosen == null) { onChoose(null) }
            stacks.forEach { s -> androidx.compose.runtime.key(s.id) { TriageChip("${s.title} · ${s.newCount}", chosen == s.id) { onChoose(s.id) } } }
        }
    }
}

@Composable
private fun TriageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label,
            Modifier.background(if (selected) MaterialTheme.colorScheme.primary else tonalFill(Tonal.Quiet), AppShapes.chip)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary, maxLines = 1
        )
    }
}

/** The card: who and when, the category, the bold headline, the short summary, tags, and the footer. Its content is one spoken sentence with the four actions. */
@Composable
private fun CardFace(
    card: TriageCard,
    message: ClassifiedMessage?,
    index: Int,
    count: Int,
    hint: TriageDeckState?,
    modifier: Modifier,
    onMove: (TriageMove) -> Unit,
    onCategory: () -> Unit,
    onOpen: () -> Unit
) {
    val shape = AppShapes.card
    val surface = MaterialTheme.colorScheme.surface
    val face = lerp(surface, MaterialTheme.colorScheme.onSurface, 0.03f)
    val ink = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    // What letting go does: archiving is calm and neutral (never an alarm red), keeping takes the accent. Opaque mixes over the card's own face.
    val sweepTint = lerp(face, ink, 0.14f)
    val keepTint = lerp(face, primary, 0.2f)
    val spokenTags = card.tags.joinToString(", ") { it.text }
    val spoken = privateSpeech(
        buildString {
            append("Card ${index + 1} of $count. ").append(card.title)
            if (card.verified) append(", verified sender")
            append(". ").append(card.headline).append(". ").append(card.summary.replace('\n', '.').replace("..", "."))
            if (spokenTags.isNotEmpty()) append(". ").append(spokenTags)
        }
    )
    CapFontScale(1.3f) {
        Box(modifier.clip(shape).background(face).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape).clipToBounds()) {
            Column(Modifier.fillMaxSize().padding(Spacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clearAndSetSemantics { }) {
                        if (message != null) {
                            MerchantChip(
                                message.merchant.logo, imageKey = message.merchant.imageKey, name = message.merchant.name,
                                originalName = message.merchant.originalName, size = 44.dp
                            )
                        } else Spacer(Modifier.size(44.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.md).clearAndSetSemantics { }) {
                        SenderTitle(SenderLabel(card.title, card.verified), style = MaterialTheme.typography.titleSmall)
                        Caption(DisplayFormatters.listStamp(card.dateMillis))
                    }
                    if (message != null && message.content !is MessageContent.Otp) {
                        Box(
                            Modifier.heightIn(min = 48.dp).widthIn(max = 132.dp).tappable(onClick = onCategory, onClickLabel = "Change category"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                categoryText(message) + " ▾",
                                Modifier.background(tonalFill(Tonal.Raised), AppShapes.chip).padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(top = Spacing.lg)
                        .semantics(mergeDescendants = true) {
                            contentDescription = spoken
                            customActions = listOf(
                                CustomAccessibilityAction("Sweep it") { onMove(TriageMove.SWEEP); true },
                                CustomAccessibilityAction("Got it") { onMove(TriageMove.KEEP); true },
                                CustomAccessibilityAction("Previous card") { onMove(TriageMove.PREVIOUS); true },
                                CustomAccessibilityAction("Next card") { onMove(TriageMove.NEXT); true }
                            )
                        }
                ) {
                    Text(
                        card.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, maxLines = scaledLines(4), overflow = TextOverflow.Ellipsis
                    )
                    if (card.summary.isNotEmpty()) {
                        Text(
                            card.summary, Modifier.padding(top = Spacing.md), style = TypeRole.body, color = ink.copy(alpha = 0.86f),
                            maxLines = scaledLines(6), overflow = TextOverflow.Ellipsis
                        )
                    }
                    TagRow(card, Modifier.padding(top = Spacing.md))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.08f)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Caption("Summary by Zyke · on this phone", Modifier.weight(1f), maxLines = 2)
                    Box(Modifier.heightIn(min = 48.dp).tappable(onClick = onOpen, onClickLabel = "Open the message"), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            "Open message ↗", Modifier.padding(start = Spacing.sm), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1
                        )
                    }
                }
            }
            if (hint != null) {
                // What letting go does, said by a tint, an icon and a word that firm up as the card nears the line: left sweeps, right keeps.
                Box(
                    Modifier.matchParentSize().graphicsLayer { alpha = (-hint.dragX / (hint.widthPx * TriageGestures.COMMIT_FRACTION)).coerceIn(0f, 1f) * HINT_ALPHA }
                        .background(sweepTint).clearAndSetSemantics { }
                ) {
                    Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Image(painter = painterResource(R.drawable.baseline_archive_24), contentDescription = null, modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(ink))
                        Text("Sweep it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ink)
                    }
                }
                Box(
                    Modifier.matchParentSize().graphicsLayer { alpha = (hint.dragX / (hint.widthPx * TriageGestures.COMMIT_FRACTION)).coerceIn(0f, 1f) * HINT_ALPHA }
                        .background(keepTint).clearAndSetSemantics { },
                    contentAlignment = Alignment.TopEnd
                ) {
                    Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("Got it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ink)
                        Image(painter = painterResource(R.drawable.baseline_check_24), contentDescription = null, modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(primary))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagRow(card: TriageCard, modifier: Modifier) {
    if (card.tags.isEmpty()) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        card.tags.forEach { tag ->
            val color = when (tag.tone) {
                TriageTone.GOOD -> AppColors.success
                TriageTone.WARNING -> AppColors.warning
                TriageTone.DANGER -> AppColors.danger
                TriageTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                tag.text, Modifier.background(tonalFill(Tonal.Quiet), AppShapes.chip).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Always visible under the deck, and the way to do everything without a gesture: Sweep it, Got it, and the two arrows that move
 * to the previous or next card. Every button is at least 48 dp high and the pairs are equal in width; chrome text stops growing at 1.3.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ControlBar(enabled: Boolean, canMove: Boolean, onMove: (TriageMove) -> Unit) {
    ChromeScale {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // The two decisions share the width equally; the two arrows are equal square keys.
            BarButton("← Sweep it", "Sweep it, archive this card", enabled, false, Modifier.weight(1f)) { onMove(TriageMove.SWEEP) }
            BarButton("Got it →", "Got it, mark read and keep it in Messages", enabled, true, Modifier.weight(1f)) { onMove(TriageMove.KEEP) }
            BarButton("↑", "Previous card", enabled && canMove, false, Modifier.width(ARROW_KEY)) { onMove(TriageMove.PREVIOUS) }
            BarButton("↓", "Next card", enabled && canMove, false, Modifier.width(ARROW_KEY)) { onMove(TriageMove.NEXT) }
        }
    }
}

@Composable
private fun BarButton(label: String, description: String, enabled: Boolean, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.heightIn(min = 52.dp)
            .background(if (primary) scheme.primary else tonalFill(Tonal.Quiet), AppShapes.control)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .tappable(onClick = onClick, enabled = enabled, onClickLabel = description)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, Modifier.padding(horizontal = Spacing.xs), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (primary) scheme.onPrimary else scheme.primary, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis
        )
    }
}
