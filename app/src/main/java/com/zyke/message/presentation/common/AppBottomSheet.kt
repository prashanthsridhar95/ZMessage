package com.zyke.message.presentation.common

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
import androidx.compose.runtime.LaunchedEffect

/**
 * The one bottom sheet for the app. It opens as tall as its content; when the content is taller than half
 * the screen it opens at the full height rather than at a preset half, and it can be dragged down to
 * dismiss. Content that still does not fit scrolls only once the sheet is full height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    LaunchedEffect(sheetState) { sheetState.expand() }
    // Read here, in the app's window: the sheet's own window can report no insets. A full-height sheet then
    // stops below the status bar and the camera cut-out instead of putting its handle behind them.
    val topInset = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.padding(top = topInset),
        content = content
    )
}
