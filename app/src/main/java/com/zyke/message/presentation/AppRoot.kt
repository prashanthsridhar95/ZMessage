package com.zyke.message.presentation

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
import com.zyke.message.presentation.common.LocalOpenSettings
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
enum class AppTab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.baseline_home_24),
    ACCOUNTS("Accounts", R.drawable.ic_accounts_24),
    MONEY("Insights", R.drawable.ic_insights_24),
    BILLS("Bills", R.drawable.baseline_receipt_24),
    INBOX("Messages", R.drawable.baseline_message_24)
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
    content: @Composable (tab: AppTab, modifier: Modifier, navigate: (AppTab) -> Unit) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(StartTab) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()
    val reduceMotion = rememberReduceMotion()

    BackHandler(enabled = tab != StartTab) { tab = StartTab }

    CompositionLocalProvider(LocalOpenSettings provides { showSettings = true }) {
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
    }
    }
}

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
 * A tab's icon, alive to being chosen: it springs up and tilts once as it is selected, settles a little larger and
 * lifted, and eases back when another tab is chosen. With reduced motion it only changes size.
 */
@Composable
private fun AnimatedTabIcon(tab: AppTab, selected: Boolean, badge: Int) {
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (selected) SELECTED_SCALE else 1f,
        animationSpec = if (reduceMotion) tween(0) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tab-scale"
    )
    val lift by animateFloatAsState(
        targetValue = if (selected) -LIFT_DP else 0f,
        animationSpec = if (reduceMotion) tween(0) else spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tab-lift"
    )
    // One quick tilt each way when the tab becomes the chosen one.
    val tilt = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected && !reduceMotion) {
            tilt.animateTo(-TILT_DEGREES, tween(90))
            tilt.animateTo(TILT_DEGREES, tween(120))
            tilt.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        } else {
            tilt.snapTo(0f)
        }
    }
    val modifier = Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationY = lift.dp.toPx()
        rotationZ = tilt.value
    }
    if (badge > 0) {
        BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else badge.toString()) } }, modifier = modifier) {
            Icon(painterResource(tab.icon), contentDescription = null)
        }
    } else {
        Icon(painterResource(tab.icon), contentDescription = null, modifier = modifier)
    }
}

private const val SELECTED_SCALE = 1.18f
private const val LIFT_DP = 2f
private const val TILT_DEGREES = 12f
