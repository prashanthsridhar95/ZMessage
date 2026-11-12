package com.zyke.message.presentation.common

import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.motion.Motion
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One page that is open: its title, what to draw, and what to do when it is left. */
@Stable
class PageEntry {
    var title by mutableStateOf("")
    var onBack by mutableStateOf<() -> Unit>({})
    var content by mutableStateOf<@Composable ColumnScope.() -> Unit>({})
    var floating by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)
    var footer by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
    var scrollable by mutableStateOf(true)
}

@Stable
class PageHost {
    val entries = mutableStateListOf<PageEntry>()
}

val LocalPageHost = staticCompositionLocalOf<PageHost?> { null }

/**
 * A page of its own, over the whole app: a title with a back arrow, and the content scrolling beneath. It slides in
 * from the side (a third of the width) while it fades in, and leaves the same way backwards. Sheets opened from it are drawn above it, and Back leaves it.
 */
@Composable
fun AppPage(
    title: String,
    onBack: () -> Unit,
    floating: (@Composable BoxScope.() -> Unit)? = null,
    /** Pinned under the scrolling content, above the keyboard: where a form's save button lives. */
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    /** False when the content scrolls by itself (a lazy list): it then gets the room under the title, unpadded. */
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val host = LocalPageHost.current ?: return
    val entry = remember { PageEntry() }
    SideEffect {
        entry.title = title
        entry.onBack = onBack
        entry.content = content
        entry.floating = floating
        entry.footer = footer
        entry.scrollable = scrollable
    }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

@Composable
fun PageHostLayer(host: PageHost) {
    host.entries.forEach { entry -> key(entry) { PageLayer(entry) } }
}

@Composable
private fun PageLayer(entry: PageEntry) {
    val scope = rememberCoroutineScope()
    val motion = rememberMotion()
    // 1 is away (a third of the width to the right, transparent), 0 is in place.
    val away = remember { Animatable(1f) }
    LaunchedEffect(Unit) { away.animateTo(0f, motion.tween(Motion.STANDARD_MS, Motion.Standard)) }

    fun leave() {
        scope.launch {
            away.animateTo(1f, motion.exit(Motion.STANDARD_MS))
            entry.onBack()
        }
    }
    BackHandler { leave() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The page and anything floating over it move and fade together. Read inside graphicsLayer: a frame redraws, it does not recompose.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = away.value * size.width * Motion.PAGE_SLIDE_FRACTION
                alpha = 1f - away.value
            }
        ) {
        // The page paints its own surface, so it must also say what text on it looks like: without this Text falls back to
        // Compose's default content colour, black, which vanishes on a dark page.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                // Nothing under the page may be touched through it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .statusBarsPadding()
                // The page rises with the keyboard, so the field being typed in and the footer stay in view.
                .imePadding()
                .semantics { paneTitle = entry.title }
                // Escape on a hardware keyboard goes back, like Back.
                .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { leave(); true } else false }
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(56.dp).height(56.dp).clickable(role = Role.Button, onClickLabel = "Back", onClick = ::leave),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.zyke.message.R.drawable.baseline_arrow_back_24),
                        contentDescription = "Back",
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                }
                Text(
                    entry.title,
                    Modifier.heading(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val footer = entry.footer
            Column(
                if (entry.scrollable) {
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg)
                        .then(if (footer == null) Modifier.navigationBarsPadding() else Modifier).padding(bottom = Spacing.xxl)
                } else {
                    Modifier.weight(1f).fillMaxWidth().then(if (footer == null) Modifier.navigationBarsPadding() else Modifier)
                }
            ) {
                entry.content(this)
            }
            if (footer != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(top = Spacing.md).navigationBarsPadding().padding(bottom = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) { footer() }
            }
        }
        }
        entry.floating?.invoke(this)
        }
    }
}
