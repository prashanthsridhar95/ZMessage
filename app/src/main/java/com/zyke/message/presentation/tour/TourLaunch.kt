package com.zyke.message.presentation.tour

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.tour.TourOffer
import com.zyke.message.domain.tour.TourPolicy
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.messages.components.Caption

/**
 * The first-launch hook. Composed by the activity once the app is showing (not behind the lock screen, not during Welcome).
 * [permissionsSettled] turns true when the SMS explainer and the other permission steps are over (answered or dismissed),
 * so the invitation never competes with them. The decision itself is `TourPolicy.offer`, which is pure and tested.
 */
@Composable
fun TourLaunch(controller: TourController, permissionsSettled: Boolean) {
    val state by controller.seen.collectAsState()
    val offer = TourPolicy.offer(state)
    LaunchedEffect(offer, permissionsSettled, controller.isRunning) {
        if (!permissionsSettled || controller.isRunning) return@LaunchedEffect
        when (offer) {
            // The process ended mid-tour (or the screen turned): pick it up where it stopped.
            TourOffer.RESUME -> controller.resume(state.savedIndex)
            else -> if (state.running) controller.closeStaleRecord()
        }
    }
    if (offer == TourOffer.INVITE && permissionsSettled && !controller.isRunning) {
        AlertDialog(
            onDismissRequest = controller::declineInvite,
            title = { Text("Take a quick tour?") },
            text = {
                Text(
                    "See what is where in about 3 minutes. The tour opens each screen for you and explains it. " +
                        "You can find it any time in Settings, Help."
                )
            },
            confirmButton = { AppButton("Start tour", { controller.start() }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            dismissButton = { AppButton("Maybe later", controller::declineInvite, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

/** True when the one-time "New here?" card should be on Home: the tour was never finished and the card was never closed. */
@Composable
fun rememberTourBannerVisible(): Boolean {
    val tour = LocalTour.current ?: return false
    val state by tour.seen.collectAsState()
    return !tour.isRunning && TourPolicy.offer(state) == TourOffer.BANNER
}

/** The quiet offer on Home for anyone who is not shown the tour up front (restored, updated, or "Maybe later"). */
@Composable
fun TourBanner(modifier: Modifier = Modifier) {
    val tour = LocalTour.current ?: return
    FintechCard(modifier) {
        Caption("NEW HERE?")
        Text("Take the app tour", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Caption("About 3 minutes. It opens each screen for you and shows what is where.", Modifier.padding(top = 4.dp), maxLines = 3)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Start tour", { tour.start() }, size = ButtonSize.COMPACT)
            AppButton("Not now", tour::dismissBanner, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    }
}
