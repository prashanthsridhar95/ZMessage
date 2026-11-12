package com.zyke.message.presentation.messages.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.rememberMotion

/**
 * The line under the selected tab, sliding to the next one in [Motion.TAB_MS] (200 ms) on the standard easing, and
 * stepping at once when motion is reduced. Material's own indicator uses a spring; this one uses the tokens.
 */
@Composable
internal fun SlidingTabIndicator(tabPositions: List<TabPosition>, selectedIndex: Int) {
    val motion = rememberMotion()
    val target = tabPositions.getOrNull(selectedIndex) ?: return
    val left by animateDpAsState(target.left, motion.tween(Motion.TAB_MS), label = "tab-left")
    val width by animateDpAsState(target.width, motion.tween(Motion.TAB_MS), label = "tab-width")
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentSize(Alignment.BottomStart)
            .offset(x = left)
            .width(width)
            .height(2.dp)
            .background(MaterialTheme.colorScheme.primary)
    )
}
