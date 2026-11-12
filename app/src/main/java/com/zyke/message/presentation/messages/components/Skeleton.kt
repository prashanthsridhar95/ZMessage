package com.zyke.message.presentation.messages.components

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
 * Placeholder rows shown while the inbox loads: still, rounded blocks that fade in once. There is no shimmer or pulse
 * (a loop would keep drawing for as long as the data is slow), so the placeholder costs nothing after its first frame.
 *
 * The geometry is the real row's (MessageRow): the same gutters and vertical padding, the 48 dp tile, a title and a
 * caption line in the text's own heights, and an amount at the end, so the list that replaces it does not jump.
 */
@Composable
fun MessageListSkeleton(modifier: Modifier = Modifier, rows: Int = 8) {
    // One announcement instead of a screenful of empty boxes.
    Column(modifier.fadeInOnce().clearAndSetSemantics { contentDescription = "Loading messages" }) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBlock(Modifier.size(RowTile), corner = RowTile / 4)
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f)) {
                    // A title line is about 24 dp tall and a caption about 16: the blocks sit centred in boxes of those heights.
                    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                        SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(14.dp), corner = Radius.chip / 3)
                    }
                    Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
                        SkeletonBlock(Modifier.fillMaxWidth(0.3f).height(10.dp), corner = Radius.chip / 3)
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                SkeletonBlock(Modifier.width(64.dp).height(18.dp), corner = Radius.chip / 3)
            }
        }
    }
}
