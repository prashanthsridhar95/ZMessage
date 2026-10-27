package com.zyke.message.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.usecase.LoadProgress
import com.zyke.message.notifications.BackfillService
import com.zyke.message.presentation.messages.components.Caption

/**
 * One quiet line while older months of a large inbox are still being read. The newest month is already on screen,
 * so nothing waits on this; the numbers grow as older months arrive.
 */
@Composable
internal fun ReadingNote(progress: LoadProgress) {
    AnimatedVisibility(progress.isWorking && progress.monthsTotal > 0) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Caption("Reading older messages · ${BackfillService.describe(progress)}")
            LinearProgressIndicator(
                progress = { progress.monthsLoaded.toFloat() / progress.monthsTotal.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
