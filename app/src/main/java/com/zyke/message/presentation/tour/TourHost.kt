package com.zyke.message.presentation.tour

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.domain.tour.TourGroup
import com.zyke.message.domain.tour.TourStep
import com.zyke.message.domain.tour.TourTiming
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.rememberReduceMotion

private val SPOT_PAD = 8.dp
private val SPOT_RADIUS = 18.dp
private const val SCRIM_ALPHA = 0.74f
private const val SCRIM_ALPHA_SOFT = 0.38f
private const val TALL_TARGET = 0.6f
private const val CARD_MAX_SHARE = 0.62f
private const val SWIPE_DP = 72

/**
 * The tour on screen, drawn over everything except the snackbar: a dimmed scrim with a rounded hole around the target,
 * and a coach card on the side with more room. Touches on the scrim are swallowed (so nothing underneath is changed and
 * nothing is dismissed by accident); the card has Back, Next, Skip, Jump to and Auto-play, swipe and the system Back.
 */
@Composable
fun TourHost(controller: TourController, registry: TourRegistry) {
    val step = controller.currentStep ?: return
    val progress = controller.progress ?: return
    val reduceMotion = rememberReduceMotion()
    val lite = com.zyke.message.presentation.common.LocalLite.current
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(step.id) { controller.prepare(step, registry) }

    val touchExploration = rememberTouchExploration()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isLast = progress.remaining(controller.steps) == 0
    LaunchedEffect(step.id, controller.autoPlay, controller.interaction, controller.preparing, touchExploration, confirmLeave) {
        // Never while a screen reader is on, never through a step that asks for an action, never on the last step.
        if (!controller.autoPlay || controller.preparing || touchExploration || step.needsAction || confirmLeave || isLast) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            kotlinx.coroutines.delay(TourTiming.dwellMillis(step))
            controller.next()
        }
    }

    val onBack: () -> Unit = { if (progress.index == 0) confirmLeave = true else controller.back() }
    key(controller.navVersion) { BackHandler(enabled = true, onBack = onBack) }

    val scrimColor = MaterialTheme.colorScheme.scrim
    val ringColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val spec = if (reduceMotion) snap<Rect>() else spring(stiffness = Spring.StiffnessMediumLow)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                hostOrigin = it.positionInWindow()
                registry.windowWidth = it.size.width.toFloat()
                registry.windowHeight = it.size.height.toFloat()
            }
            // The scrim swallows every touch: the screens under it can't be used, and tapping it dismisses nothing.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) { awaitPointerEvent().changes.forEach { it.consume() } }
                }
            }
    ) {
        val bounds = controller.target.anchor?.let { registry.bounds[it] }
        val width = registry.windowWidth
        val height = registry.windowHeight
        val padPx = with(density) { SPOT_PAD.toPx() }
        val local = bounds?.takeIf { it.width > 1f && it.height > 1f }?.translate(-hostOrigin.x, -hostOrigin.y)
        val tall = local != null && height > 0f && local.height > height * TALL_TARGET
        val cutout = if (local != null && !tall) local.inflate(padPx) else null
        val collapsed = Rect(Offset(width / 2f, height / 2f), Size(0f, 0f))
        val animated by animateRectAsState(cutout ?: collapsed, spec, label = "tour-spot")
        val scrimAlpha by animateFloatAsState(
            if (tall) SCRIM_ALPHA_SOFT else SCRIM_ALPHA,
            if (reduceMotion) snap() else tween(200), label = "tour-scrim"
        )
        val radius = with(density) { SPOT_RADIUS.toPx() }
        val ringWidth = with(density) { 2.dp.toPx() }

        // Decorative: the spotlight is for eyes; the card carries all the meaning for a screen reader.
        if (lite) {
            // Lite mode: no off-screen layer. The dim is four plain rectangles around the target (square cut-out),
            // with the same rounded ring on top; far cheaper for a weak GPU than clearing a hole in a layer.
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                val dim = scrimColor.copy(alpha = scrimAlpha)
                if (animated.width > 1f && animated.height > 1f) {
                    val top = animated.top.coerceIn(0f, size.height)
                    val bottom = animated.bottom.coerceIn(0f, size.height)
                    val left = animated.left.coerceIn(0f, size.width)
                    val right = animated.right.coerceIn(0f, size.width)
                    drawRect(dim, Offset.Zero, Size(size.width, top))
                    drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
                    drawRect(dim, Offset(0f, top), Size(left, bottom - top))
                    drawRect(dim, Offset(right, top), Size(size.width - right, bottom - top))
                    drawRoundRect(ringColor, animated.topLeft, animated.size, CornerRadius(radius), style = Stroke(ringWidth))
                } else {
                    drawRect(dim)
                }
            }
        } else {
        Canvas(
            Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).clearAndSetSemantics { }
        ) {
            drawRect(scrimColor.copy(alpha = scrimAlpha))
            if (animated.width > 1f && animated.height > 1f) {
                drawRoundRect(scrimColor, animated.topLeft, animated.size, CornerRadius(radius), blendMode = BlendMode.Clear)
                drawRoundRect(ringColor, animated.topLeft, animated.size, CornerRadius(radius), style = Stroke(ringWidth))
            }
        }
        }

        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val roomForCard = maxHeight
            val below = local != null && !tall && local.center.y < height / 2f
            val above = local != null && !tall && !below
            val align = when {
                local == null || tall -> Alignment.BottomCenter
                below -> Alignment.BottomCenter
                else -> Alignment.TopCenter
            }
            Box(Modifier.align(align)) {
                CoachCard(
                    controller = controller, step = step, maxCardHeight = roomForCard * CARD_MAX_SHARE,
                    targetCenterX = if (local != null && !tall) local.center.x + hostOrigin.x else null,
                    caretOnTop = below, caretOnBottom = above,
                    touchExploration = touchExploration,
                    onLeave = { confirmLeave = true }
                )
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave the tour?") },
            text = { Text("You can take it again any time from Settings, Help.") },
            confirmButton = { AppButton("Leave", { confirmLeave = false; controller.skip() }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            dismissButton = { AppButton("Keep going", { confirmLeave = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoachCard(
    controller: TourController,
    step: TourStep,
    maxCardHeight: androidx.compose.ui.unit.Dp,
    targetCenterX: Float?,
    caretOnTop: Boolean,
    caretOnBottom: Boolean,
    touchExploration: Boolean,
    onLeave: () -> Unit
) {
    val steps = controller.steps
    val progress = controller.progress ?: return
    val total = steps.size
    val number = progress.index + 1
    val cardColor = MaterialTheme.colorScheme.surface
    val direction = LocalLayoutDirection.current
    val swipePx = with(LocalDensity.current) { SWIPE_DP.dp.toPx() }
    var cardLeft by remember { mutableStateOf(0f) }
    var cardWidth by remember { mutableStateOf(0f) }
    var menuOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(step.id, controller.preparing) {
        if (!controller.preparing) runCatching { focus.requestFocus() }
    }

    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        if (caretOnTop) Caret(targetCenterX, cardLeft, cardWidth, cardColor, pointUp = true)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { cardLeft = it.positionInWindow().x; cardWidth = it.size.width.toFloat() }
                .semantics { paneTitle = step.title; liveRegion = LiveRegionMode.Polite }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) controller.touched()
                        }
                    }
                }
                .pointerInput(step.id, direction) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            // Swiping towards the start edge goes on, like turning a page; mirrored in right-to-left.
                            val forward = if (direction == LayoutDirection.Ltr) drag < -swipePx else drag > swipePx
                            val backward = if (direction == LayoutDirection.Ltr) drag > swipePx else drag < -swipePx
                            if (forward) controller.next() else if (backward) controller.back()
                        },
                        onDragCancel = { drag = 0f }
                    ) { _, amount -> drag += amount }
                },
            shape = RoundedCornerShape(24.dp),
            color = cardColor,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.heightIn(max = maxCardHeight).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Step $number of $total",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(step.group.chapter, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GroupBar(steps, progress.index, Modifier.padding(top = 8.dp, bottom = 12.dp))
                    Text(
                        step.title,
                        Modifier.focusRequester(focus).focusable().semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        step.body, Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface
                    )
                    step.tip?.let {
                        Text(
                            "Tip: $it", Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    step.actionLabel?.let {
                        Text(
                            it, Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                        )
                    }
                    val missing = !controller.preparing && controller.target.anchor == null && step.anchor != null
                    if (missing && step.preview == null) {
                        Text(
                            step.spotlightHint ?: "This shows up here once there is something to show.", Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val preview = step.preview
                    if (preview != null && controller.target.placeholder && !controller.preparing) {
                        TourPreview(preview, Modifier.padding(top = 12.dp))
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    AppButton(
                        "Skip tour", { if (progress.remaining(steps) > 2) onLeave() else controller.skip() },
                        Modifier.semantics { contentDescription = "Skip the tour" },
                        style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                    )
                    Box {
                        AppButton(
                            "Jump to", { menuOpen = true },
                            Modifier.semantics { contentDescription = "Jump to a chapter" },
                            style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            TourGroup.entries.filter { g -> steps.any { it.group == g } }.forEach { group ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            group.chapter,
                                            fontWeight = if (group == step.group) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = { menuOpen = false; controller.jumpTo(group) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                    // A screen reader user moves at their own pace: no timer to pause, so the control is not offered.
                    if (!touchExploration) {
                        AppButton(
                            if (controller.autoPlay) "Pause" else "Auto-play",
                            { controller.updateAutoPlay(!controller.autoPlay) },
                            Modifier.semantics { contentDescription = if (controller.autoPlay) "Pause auto-advance" else "Play auto-advance" },
                            style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton(
                        "Back", controller::back,
                        Modifier.weight(1f).semantics { contentDescription = "Back, to step ${(number - 1).coerceAtLeast(1)} of $total" },
                        style = ButtonStyle.OUTLINE, enabled = progress.index > 0
                    )
                    AppButton(
                        if (progress.remaining(steps) == 0) "Finish" else "Next", controller::next,
                        Modifier.weight(1f).semantics {
                            contentDescription = if (progress.remaining(steps) == 0) "Finish the tour" else "Next, step ${number + 1} of $total"
                        }
                    )
                }
            }
        }
        if (caretOnBottom) Caret(targetCenterX, cardLeft, cardWidth, cardColor, pointUp = false)
    }
}

/** A small arrow joining the card to the spotlight. Decorative. */
@Composable
private fun Caret(
    targetCenterX: Float?, cardLeft: Float, cardWidth: Float, color: androidx.compose.ui.graphics.Color, pointUp: Boolean
) {
    if (targetCenterX == null || cardWidth <= 0f) return
    val density = LocalDensity.current
    val w = with(density) { 22.dp.toPx() }
    val margin = with(density) { 28.dp.toPx() }
    val x = (targetCenterX - cardLeft - w / 2f).coerceIn(margin, (cardWidth - margin - w).coerceAtLeast(margin))
    Canvas(
        Modifier.offset { IntOffset(x.toInt(), 0) }.size(width = 22.dp, height = 10.dp).clearAndSetSemantics { }
    ) {
        val path = Path().apply {
            if (pointUp) { moveTo(0f, size.height); lineTo(size.width / 2f, 0f); lineTo(size.width, size.height) }
            else { moveTo(0f, 0f); lineTo(size.width / 2f, size.height); lineTo(size.width, 0f) }
            close()
        }
        drawPath(path, color)
    }
}

/** One segment per chapter: full for chapters done, partly filled for this one, an outline for those to come. Decorative. */
@Composable
private fun GroupBar(steps: List<TourStep>, index: Int, modifier: Modifier = Modifier) {
    val groups = remember(steps) { steps.map { it.group }.distinct() }
    val current = steps.getOrNull(index)?.group
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Row(modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        groups.forEach { group ->
            val inGroup = steps.withIndex().filter { it.value.group == group }
            val done = inGroup.count { it.index <= index }
            val share = when {
                current == group -> done.toFloat() / inGroup.size
                inGroup.last().index < index -> 1f
                else -> 0f
            }
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(empty)) {
                if (share > 0f) Box(Modifier.fillMaxWidth(share).height(4.dp).background(filled))
            }
        }
    }
}

@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var on by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        runCatching { manager?.addTouchExplorationStateChangeListener(listener) }
        onDispose { runCatching { manager?.removeTouchExplorationStateChangeListener(listener) } }
    }
    return on
}
