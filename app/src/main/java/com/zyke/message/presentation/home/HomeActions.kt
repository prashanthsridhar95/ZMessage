package com.zyke.message.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.home.QuickActions
import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.tour.tourAnchor

/**
 * Home's quick actions, bottom right above the bottom bar: "Add entry" (56 dp, rounded square, tonal; carries its words only at the top of Home)
 * with the small round "Ask AI" (48 dp, secondary tone) stacked above it 12 dp apart. Flat like the rest of the app: no elevation, a tonal fill.
 * They hide together when the list moves up the screen and return when it moves back ([QuickActions.shown]); the list keeps
 * [QuickActions.reservedBottomDp] under its last item, so they never rest on an amount.
 */
@Composable
fun HomeQuickActions(visible: Boolean, extended: Boolean, askShown: Boolean, onAdd: () -> Unit, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    val motion = rememberMotion()
    val enter = if (motion.reduced) EnterTransition.None else fadeIn(motion.tween(Motion.STANDARD_MS)) + expandHorizontally(motion.tween(Motion.STANDARD_MS))
    val exit = if (motion.reduced) ExitTransition.None else fadeOut(motion.exit(Motion.QUICK_MS)) + shrinkHorizontally(motion.exit(Motion.QUICK_MS))
    AnimatedVisibility(visible, modifier = modifier.padding(QuickActions.MARGIN_DP.dp), enter = enter, exit = exit) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(QuickActions.GAP_DP.dp)) {
            if (askShown) {
                // Centred over the plus when "Add entry" is icon-only: 56 - 48 = 8, so 4 dp in from the edge.
                FloatingActionButton(
                    onClick = onAsk,
                    modifier = Modifier.padding(end = ((QuickActions.ADD_DP - QuickActions.ASK_DP) / 2).dp).size(QuickActions.ASK_DP.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                ) { Icon(painterResource(R.drawable.ic_ask_ai_24), contentDescription = "Ask AI", Modifier.size(22.dp)) }
            }
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.tourAnchor(TourAnchor.HOME_ADD),
                shape = AppShapes.control,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
            ) {
                Row(Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Add entry" }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_tile_add), contentDescription = null, Modifier.size(24.dp))
                    AnimatedVisibility(
                        extended,
                        enter = if (motion.reduced) EnterTransition.None else expandHorizontally(motion.tween<IntSize>(Motion.STANDARD_MS)) + fadeIn(motion.tween(Motion.STANDARD_MS)),
                        exit = if (motion.reduced) ExitTransition.None else shrinkHorizontally(motion.exit<IntSize>(Motion.QUICK_MS)) + fadeOut(motion.exit(Motion.QUICK_MS))
                    ) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text("Add entry", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
