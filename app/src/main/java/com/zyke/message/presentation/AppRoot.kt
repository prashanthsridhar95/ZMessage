package com.zyke.message.presentation

import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import com.zyke.message.presentation.common.LinkSheet
import com.zyke.message.presentation.common.MinimisedLink
import com.zyke.message.presentation.common.WebSession
import com.zyke.message.presentation.common.LocalOpenLink
import com.zyke.message.presentation.common.LocalOpenSettings
import com.zyke.message.presentation.common.isWebLink
import com.zyke.message.presentation.common.openExternally
import com.zyke.message.presentation.common.Motion
import com.zyke.message.presentation.common.rememberReduceMotion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.zyke.message.presentation.common.SheetHostLayer
import com.zyke.message.presentation.common.SheetHost
import com.zyke.message.presentation.common.LocalSheetHost
import com.zyke.message.presentation.common.LocalAppSnackbar
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R

private const val TAB_SLIDE_DIVISOR = 12

/** Five places, money first: how things stand, the accounts, the insights, what is due, and the messages behind it all. */
enum class AppTab(val label: String, val icon: Int, val outline: Int) {
    HOME("Home", R.drawable.baseline_home_24, R.drawable.ic_nav_home_outline),
    ACCOUNTS("Accounts", R.drawable.ic_accounts_24, R.drawable.ic_nav_accounts_outline),
    MONEY("Insights", R.drawable.ic_insights_24, R.drawable.ic_nav_insights_outline),
    BILLS("Bills", R.drawable.baseline_receipt_24, R.drawable.ic_nav_bills_outline),
    INBOX("Messages", R.drawable.baseline_message_24, R.drawable.ic_nav_messages_outline)
}

/** The tab the app opens on, and the one Back returns to before leaving. */
val StartTab = AppTab.HOME

/**
 * Tab shell. The app opens on Home. Each tab keeps its scroll position while another is showing,
 * and Back from any other tab returns to Home before leaving the app, so the way out is always
 * the same.
 */
@Composable
fun AppRoot(
    inboxBadge: Int,
    toolbar: @Composable (tab: AppTab, openSettings: () -> Unit) -> Unit,
    /** Settings is a bottom sheet over whatever tab is showing; it is only composed while open. */
    settings: @Composable (onDismiss: () -> Unit) -> Unit,
    /** A line shown under the toolbar on every tab, e.g. while older messages are still being read. */
    topNote: @Composable () -> Unit = {},
    /** The search page, over whatever tab is showing; it is only composed while open. [navigate] changes tab. */
    search: @Composable (onDismiss: () -> Unit, navigate: (AppTab) -> Unit) -> Unit = { _, _ -> },
    content: @Composable (tab: AppTab, modifier: Modifier, navigate: (AppTab) -> Unit) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(StartTab) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val web = remember { WebSession(context) }
    androidx.compose.runtime.DisposableEffect(web) { onDispose { web.close() } }
    val stateHolder = rememberSaveableStateHolder()
    val reduceMotion = rememberReduceMotion()

    BackHandler(enabled = tab != StartTab) { tab = StartTab }

    val sheetHost = remember { SheetHost() }
    val pageHost = remember { com.zyke.message.presentation.common.PageHost() }
    val snackbar = remember { SnackbarHostState() }

    CompositionLocalProvider(
        LocalSheetHost provides sheetHost,
        com.zyke.message.presentation.common.LocalPageHost provides pageHost,
        LocalAppSnackbar provides snackbar,
        LocalOpenSettings provides { showSettings = true },
        com.zyke.message.presentation.common.LocalOpenSearch provides { showSearch = true },
        LocalOpenLink provides { url -> if (isWebLink(url)) web.open(url) else openExternally(context, url) }
    ) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        // The Zyke toolbar handles the status bar and the bottom bar the navigation bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { toolbar(tab) { showSettings = true } },
        bottomBar = { AppBottomBar(selected = tab, inboxBadge = inboxBadge, onSelect = { tab = it }) }
    ) { padding ->
        Column(Modifier.padding(padding)) {
        topNote()
        Box(Modifier.weight(1f)) {
        // Tabs slide the way you moved along the bar, so where you are in the app is felt, not just read.
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (reduceMotion) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (fadeIn(tween(Motion.STANDARD_MS)) + slideInHorizontally(tween(Motion.STANDARD_MS)) { direction * it / TAB_SLIDE_DIVISOR })
                    .togetherWith(fadeOut(tween(Motion.QUICK_MS)))
            },
            label = "tab"
        ) { shown ->
            stateHolder.SaveableStateProvider(shown.name) {
                content(shown, Modifier) { tab = it }
            }
        }
        }
        }
        if (showSettings) settings { showSettings = false }
        if (showSearch) search({ showSearch = false }) { tab = it }
        if (web.isOpen && !web.minimised) LinkSheet(web)
    }
    // Drawn after the screens, so over them; the minimised page and the snackbar are drawn after the sheets, so over those.
    // Pages go over the screens; the sheets opened from a page, then the web button and the snackbar, go over those.
    com.zyke.message.presentation.common.PageHostLayer(pageHost)
    SheetHostLayer(sheetHost)
    if (web.isOpen && web.minimised) MinimisedLink(web, Modifier.statusBarsPadding().navigationBarsPadding())
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = SNACKBAR_LIFT))
    }
    }
}

private val SNACKBAR_LIFT = 80.dp

@Composable
fun AppBottomBar(selected: AppTab, inboxBadge: Int, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(
            Modifier.padding(horizontal = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            AppTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { AnimatedTabIcon(tab, selected = tab == selected, badge = if (tab == AppTab.INBOX) inboxBadge else 0) },
                    label = { Text(tab.label) }
                )
            }
        }
    }
}

/**
 * A tab's icon, drawn as an outline. Choosing the tab fills it in: the solid icon grows in over the outline and the
 * outline fades out, a smooth settle rather than a jump. With reduced motion the change is instant.
 */
@Composable
private fun AnimatedTabIcon(tab: AppTab, selected: Boolean, badge: Int) {
    val reduceMotion = rememberReduceMotion()
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else FILL_MS),
        label = "tab-fill"
    )
    val icon: @Composable () -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(tab.outline), contentDescription = null, modifier = Modifier.graphicsLayer { alpha = 1f - fill })
            Icon(
                painterResource(tab.icon), contentDescription = null,
                modifier = Modifier.graphicsLayer {
                    alpha = fill
                    val grow = FILL_FROM_SCALE + (1f - FILL_FROM_SCALE) * fill
                    scaleX = grow
                    scaleY = grow
                }
            )
        }
    }
    if (badge > 0) {
        BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else badge.toString()) } }) { icon() }
    } else {
        icon()
    }
}

private const val FILL_MS = 260
private const val FILL_FROM_SCALE = 0.7f
