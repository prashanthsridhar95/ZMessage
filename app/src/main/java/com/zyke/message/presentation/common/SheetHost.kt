package com.zyke.message.presentation.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One sheet that is open: what to draw in it and what to do when it is dismissed. */
@Stable
class SheetEntry {
    var onDismiss by mutableStateOf<() -> Unit>({})
    var content by mutableStateOf<@Composable ColumnScope.() -> Unit>({})
    var inset by mutableStateOf(20.dp)
    var partial by mutableStateOf(false)
}

/** The sheets that are open, oldest first. They are drawn by [SheetHostLayer], inside the app's own window. */
@Stable
class SheetHost {
    val entries = mutableStateListOf<SheetEntry>()
}

/** Where sheets are drawn; null in previews and tests, where [AppBottomSheet] falls back to a platform sheet. */
val LocalSheetHost = staticCompositionLocalOf<SheetHost?> { null }

private const val SCRIM_ALPHA = 0.32f
private const val ENTER_MS = 260
private const val EXIT_MS = 200
private const val DISMISS_FRACTION = 0.3f
private const val PARTIAL_HEIGHT = 0.62f
private val CORNER = 28.dp

/**
 * Draws every open sheet over the app, inside the app's own window rather than a window of their own. That is what lets
 * the minimised web page and the snackbar be drawn after it, and so stay in front of any sheet.
 */
@Composable
fun SheetHostLayer(host: SheetHost) {
    host.entries.forEach { entry -> key(entry) { SheetLayer(entry) } }
}

@Composable
private fun SheetLayer(entry: SheetEntry) {
    val scope = rememberCoroutineScope()
    // 1 is fully below the screen, 0 is fully open.
    val hidden = remember { Animatable(1f) }
    var sheetHeight by remember { mutableIntStateOf(0) }
    val topInset = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding()

    androidx.compose.runtime.LaunchedEffect(Unit) { hidden.animateTo(0f, tween(ENTER_MS)) }

    fun dismiss() {
        scope.launch {
            hidden.animateTo(1f, tween(EXIT_MS))
            entry.onDismiss()
        }
    }

    fun settle() {
        scope.launch { if (hidden.value > DISMISS_FRACTION) dismiss() else hidden.animateTo(0f, tween(EXIT_MS)) }
    }

    BackHandler { dismiss() }

    // Content that scrolls hands over to the sheet once it is at its top: pulling further down drags the sheet away.
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || hidden.value <= 0f || sheetHeight == 0) return Offset.Zero
                val next = (hidden.value + available.y / sheetHeight).coerceAtLeast(0f)
                val used = (next - hidden.value) * sheetHeight
                scope.launch { hidden.snapTo(next) }
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f || sheetHeight == 0) return Offset.Zero
                scope.launch { hidden.snapTo((hidden.value + available.y / sheetHeight).coerceAtMost(1f)) }
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (hidden.value > 0f) settle()
                return available
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxSheet = if (entry.partial) maxHeight * PARTIAL_HEIGHT else maxHeight - topInset

        // The dimmed screen behind; tapping it closes the sheet.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA * (1f - hidden.value)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::dismiss)
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxSheet)
                .offset { IntOffset(0, (hidden.value * sheetHeight).roundToInt()) }
                .onSizeChanged { sheetHeight = it.height }
                // Unseen until it has been measured, so it cannot flash at its open position before sliding up.
                .graphicsLayer { alpha = if (sheetHeight == 0) 0f else 1f }
                .nestedScroll(connection)
                // Touches on the sheet itself must not fall through to the scrim.
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = "Sheet" },
            shape = RoundedCornerShape(topStart = CORNER, topEnd = CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                // The handle: dragging it moves the sheet, and a tap on it closes the sheet.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = { settle() },
                                onDragCancel = { settle() }
                            ) { change, dy ->
                                change.consume()
                                if (sheetHeight > 0) scope.launch { hidden.snapTo((hidden.value + dy / sheetHeight).coerceIn(0f, 1f)) }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                }
                CompositionLocalProvider(LocalContentInset provides entry.inset) {
                    Column(Modifier.weight(1f, fill = false)) { entry.content(this) }
                }
            }
        }
    }
}

