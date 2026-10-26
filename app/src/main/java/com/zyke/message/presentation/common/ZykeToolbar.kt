package com.zyke.message.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.presentation.messages.components.BarIcon
import com.zyke.message.presentation.messages.components.SearchField

/** Turns the toolbar into a search box: the field replaces the logo, a close button replaces the actions. */
data class ToolbarSearch(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val onClose: () -> Unit
)

/**
 * The one bar at the top of every screen: the Zyke mark on the left, the current screen's buttons on the
 * right. Screens below it have no headers of their own, so nothing is said twice. It also owns the status
 * bar inset.
 */
@Composable
fun ZykeToolbar(
    modifier: Modifier = Modifier,
    search: ToolbarSearch? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (search != null) {
                SearchField(search.query, search.onQueryChange, Modifier.weight(1f))
                BarIcon(R.drawable.baseline_close_24, "Close search", false, 0, search.onClose)
            } else {
                Image(
                    modifier = Modifier.height(30.dp),
                    painter = painterResource(R.drawable.zyke_main),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    contentDescription = "Zyke"
                )
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}
