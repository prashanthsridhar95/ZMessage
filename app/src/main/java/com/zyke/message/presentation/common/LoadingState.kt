package com.zyke.message.presentation.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.motion.SkeletonBlock
import com.zyke.message.presentation.motion.fadeInOnce
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing

/**
 * The one loading page for anything without a skeleton of its own: still rows (a tile and two lines) that fade in once.
 * Nothing shimmers or pulses. A screen reader hears [description] once instead of a screenful of empty blocks. Put it
 * inside a `CrossfadeContent` so the real content replaces it softly.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, description: String = "Loading", rows: Int = 4) {
    Column(modifier.fadeInOnce().clearAndSetSemantics { contentDescription = description }) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBlock(Modifier.size(48.dp), corner = Radius.chip)
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                        SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(14.dp), corner = Radius.chip / 3)
                    }
                    Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
                        SkeletonBlock(Modifier.fillMaxWidth(0.3f).height(10.dp), corner = Radius.chip / 3)
                    }
                }
            }
        }
    }
}
