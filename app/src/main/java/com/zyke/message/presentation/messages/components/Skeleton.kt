package com.zyke.message.presentation.messages.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/** Placeholder rows shown while the inbox loads. */
@Composable
fun MessageListSkeleton(modifier: Modifier = Modifier, rows: Int = 7) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha"
    )
    val block = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(modifier.alpha(pulse)) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(60.dp).background(block, RoundedCornerShape(16.dp)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Spacer(Modifier.fillMaxWidth(0.5f).height(14.dp).background(block, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(8.dp))
                    Spacer(Modifier.fillMaxWidth(0.3f).height(10.dp).background(block, RoundedCornerShape(4.dp)))
                }
                Spacer(Modifier.width(12.dp))
                Spacer(Modifier.width(64.dp).height(18.dp).background(block, RoundedCornerShape(4.dp)))
            }
        }
    }
}
