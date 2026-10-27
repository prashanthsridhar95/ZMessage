package com.zyke.message.presentation

import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
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

/** Five places, each with one job: am I okay, where did it go, what is due, a code, and the messages. */
enum class AppTab(val label: String, val icon: Int) {
    MONEY("Spend", R.drawable.baseline_wallet_24),
    BILLS("Bills", R.drawable.baseline_receipt_24),
    // Home sits in the middle of the bar, with the Zyke mark for its icon.
    HOME("Home", R.drawable.ic_zyke_home),
    CODES("Codes", R.drawable.baseline_lock_24),
    INBOX("Inbox", R.drawable.baseline_message_24)
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
                content(shown, Modifier.padding(padding)) { tab = it }
            }
        }
        if (showSettings) settings { showSettings = false }
    }
    }
}

private val CircleSize = 48.dp
private val NotchGap = 4.dp
private val BarHeight = 72.dp
/** How far the raised circle rises above the bar. */
private val Rise = 28.dp

/**
 * The bar: a rounded panel with a round cut-out under the chosen tab, where that tab's icon sits raised in a
 * circle. The cut-out slides to the next tab when it changes.
 */
@Composable
fun AppBottomBar(selected: AppTab, inboxBadge: Int, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val tabs = AppTab.entries
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val barColor = MaterialTheme.colorScheme.surface

    BoxWithConstraints(modifier.fillMaxWidth().height(Rise + BarHeight + bottomInset)) {
        val itemWidth = maxWidth / tabs.size
        val targetCenter = with(density) { (itemWidth * selected.ordinal + itemWidth / 2).toPx() }
        val center by animateFloatAsState(
            targetCenter, animationSpec = if (reduceMotion) tween(0) else tween(Motion.STANDARD_MS), label = "notch"
        )
        val notchRadius = with(density) { (CircleSize / 2 + NotchGap).toPx() }
        // The circle's centre is level with the raised circle; the cut-out is that circle plus a small gap.
        val notchCenterY = with(density) { (CircleSize / 2).toPx() }
        val corner = with(density) { 8.dp.toPx() }
        val barTop = with(density) { Rise.toPx() }

        val shape = GenericShape { size, _ ->
            val dy = barTop - notchCenterY
            // Where the cut-out meets the top edge of the bar, and the angle of that point on the cut-out's circle.
            val half = kotlin.math.sqrt((notchRadius * notchRadius - dy * dy).coerceAtLeast(0f))
            val meet = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), half.toDouble())).toFloat()
            moveTo(0f, barTop + corner)
            arcTo(Rect(0f, barTop, corner * 2, barTop + corner * 2), 180f, 90f, false)
            lineTo(center - half, barTop)
            arcTo(
                Rect(center - notchRadius, notchCenterY - notchRadius, center + notchRadius, notchCenterY + notchRadius),
                180f - meet, -(180f - 2 * meet), false
            )
            lineTo(size.width - corner, barTop)
            arcTo(Rect(size.width - corner * 2, barTop, size.width, barTop + corner * 2), 270f, 90f, false)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }

        // The panel, with its shadow along the cut-out.
        Box(
            Modifier.fillMaxSize().shadow(8.dp, shape, clip = false).background(barColor, shape)
        )

        // Tab labels and the icons of the tabs that are not chosen.
        Row(Modifier.fillMaxSize().padding(top = Rise, bottom = bottomInset)) {
            tabs.forEach { tab ->
                val chosen = tab == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(role = Role.Tab, onClickLabel = "Go to ${tab.label}") { onSelect(tab) }
                        .semantics { this.selected = chosen },
                    contentAlignment = Alignment.Center
                ) {
                    if (chosen) {
                        Text(
                            tab.label,
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            TabIcon(tab, inboxBadge, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // The raised circle over the cut-out, carrying the chosen tab's icon.
        Box(
            Modifier
                .offset { IntOffset((center - CircleSize.toPx() / 2).roundToInt(), 0) }
                .size(CircleSize)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            TabIcon(selected, inboxBadge, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun TabIcon(tab: AppTab, inboxBadge: Int, tint: androidx.compose.ui.graphics.Color) {
    if (tab == AppTab.INBOX && inboxBadge > 0) {
        BadgedBox(badge = { Badge { Text(if (inboxBadge > 99) "99+" else inboxBadge.toString()) } }) {
            Icon(painterResource(tab.icon), contentDescription = null, tint = tint)
        }
    } else {
        Icon(painterResource(tab.icon), contentDescription = null, tint = tint)
    }
}
