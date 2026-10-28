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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * The one bottom sheet for the app. It opens straight to as tall as its content needs, and to the full height
 * (below the status bar) when the content is taller than the screen, with no half-way stop that hides data. It can be
 * dragged down to dismiss. Content that still does not fit scrolls once the sheet is full height.
 *
 * It is drawn inside the app's own window (see [SheetHostLayer]), so the snackbar and the minimised web page, which are
 * drawn after it, stay in front of every sheet.
 *
 * A sheet that holds a long list of choices, such as the filters, can ask for [partial]: it then opens part of the way.
 */
@Composable
fun AppBottomSheet(onDismiss: () -> Unit, contentInset: Dp = 20.dp, partial: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalSheetHost.current
    if (host == null) {
        PlatformSheet(onDismiss, contentInset, partial, content)
        return
    }
    val entry = remember { SheetEntry() }
    // The newest dismiss action and content, every time this is composed, since both capture the caller's state.
    SideEffect {
        entry.onDismiss = onDismiss
        entry.content = content
        entry.inset = contentInset
        entry.partial = partial
    }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

/** What the sheet is where there is no host to draw it in: a platform sheet in a window of its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformSheet(onDismiss: () -> Unit, contentInset: Dp, partial: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !partial)
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
