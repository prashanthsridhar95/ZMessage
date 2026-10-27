package com.zyke.message.presentation.common

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.ui.Modifier
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

/**
 * The one bottom sheet for the app. It opens straight to as tall as its content needs, and to the full height
 * (below the status bar) when the content is taller than the screen, with no half-way stop that hides data. It can be
 * dragged down to dismiss. Content that still does not fit scrolls once the sheet is full height.
 *
 * A sheet that holds a long list of choices, such as the filters, can ask for [partial]: it then opens part way and
 * is pulled up for the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(onDismiss: () -> Unit, contentInset: Dp = 20.dp, partial: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !partial)
    // Read here, in the app's window: the sheet's own window can report no insets. A full-height sheet then
    // stops below the status bar and the camera cut-out instead of putting its handle behind them.
    val topInset = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.padding(top = topInset),
        content = {
            androidx.compose.runtime.CompositionLocalProvider(LocalContentInset provides contentInset) { content() }
        }
    )
}
