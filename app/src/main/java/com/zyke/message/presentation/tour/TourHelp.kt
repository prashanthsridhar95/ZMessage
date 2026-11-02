package com.zyke.message.presentation.tour

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.tour.Tours
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/**
 * The tour's door in Settings, under Help: the whole tour, the same with auto-play, and each chapter on its own.
 * Starting closes Settings and begins at the chosen chapter; the tour ends back where the person was.
 */
@Composable
fun TourHelpSection() {
    val tour = LocalTour.current
    val steps = Tours.full()
    SectionCard("App walkthrough") {
        Caption(
            "A guided tour of every part of Zyke Message, about 3 minutes. It opens each screen for you and points out what is there. Nothing on your phone is changed.",
            maxLines = 5
        )
        AppButton(
            "Take the full tour", { tour?.start() }, Modifier.fillMaxWidth().padding(top = 12.dp),
            enabled = tour != null
        )
        AppButton(
            "Auto-play tour", { tour?.start(autoPlay = true) }, Modifier.fillMaxWidth().padding(top = 8.dp),
            style = ButtonStyle.OUTLINE, enabled = tour != null
        )
        Caption("Auto-play moves on by itself; you can pause it, go back, or skip at any time.", Modifier.padding(top = 4.dp), maxLines = 3)
    }
    SectionCard("Chapters") {
        Caption("Start the tour at one part of the app.", maxLines = 2)
        Tours.groups(steps).forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            val inGroup = Tours.stepsIn(group, steps)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .fullWidthClickable(role = Role.Button, onClickLabel = "Start the tour at ${group.chapter}") { tour?.start(group) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    RowTitle(group.chapter)
                    Caption(inGroup.joinToString(", ") { it.title }, maxLines = 2)
                }
                Text("›", color = com.zyke.message.presentation.messages.components.mutedColor())
            }
        }
    }
}
