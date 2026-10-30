package com.zyke.message.presentation.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
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
}

@Stable
class PageHost {
    val entries = mutableStateListOf<PageEntry>()
}

val LocalPageHost = staticCompositionLocalOf<PageHost?> { null }

private const val SLIDE_MS = 240

/**
 * A page of its own, over the whole app: a title with a back arrow, and the content scrolling beneath. It slides in
 * from the side. Sheets opened from it are drawn above it, and Back leaves it.
 */
@Composable
fun AppPage(title: String, onBack: () -> Unit, floating: (@Composable BoxScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalPageHost.current ?: return
    val entry = remember { PageEntry() }
    SideEffect {
        entry.title = title
        entry.onBack = onBack
        entry.content = content
        entry.floating = floating
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
    // 1 is fully off to the right, 0 is in place.
    val away = remember { Animatable(1f) }
    LaunchedEffect(Unit) { away.animateTo(0f, tween(SLIDE_MS)) }

    fun leave() {
        scope.launch {
            away.animateTo(1f, tween(SLIDE_MS))
            entry.onBack()
        }
    }
    BackHandler { leave() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        // The page and anything floating over it slide in together.
        Box(Modifier.fillMaxSize().offset { IntOffset((away.value * width).roundToInt(), 0) }) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                // Nothing under the page may be touched through it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .statusBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(56.dp).height(56.dp).clickable(onClickLabel = "Back", onClick = ::leave),
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
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp)
            ) {
                entry.content(this)
            }
        }
        entry.floating?.invoke(this)
        }
    }
}
