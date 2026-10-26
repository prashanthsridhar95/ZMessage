package com.zyke.message.presentation.common

import androidx.compose.foundation.layout.ColumnScope
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, content = content)
}
