package com.zyke.message.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.presentation.messages.components.BarIcon

/**
 * The brand bar. It is the same on every tab and every screen, so there is always one fixed
 * place that says where you are; each screen's own title and actions sit just below it.
 * It also owns the status bar inset, so screens underneath don't have to.
 */
@Composable
fun ZykeToolbar(onSettings: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                modifier = Modifier.height(30.dp),
                painter = painterResource(R.drawable.zyke_main),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                contentDescription = "Zyke"
            )
            if (onSettings != null) {
                Spacer(Modifier.weight(1f))
                BarIcon(R.drawable.baseline_settings_24, "Settings", false, 0, onSettings)
            }
        }
        HorizontalDivider(
            Modifier.padding(horizontal = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}
